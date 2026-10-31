#include "network_nft_expression.h"
#include "network_nft_objects.h"
#include "network_nft_attr.h"
#include <linux/netfilter.h>
#include <linux/netfilter/nf_tables.h>
#include <linux/netfilter/nf_nat.h>
#include <linux/netfilter/nf_tables_compat.h>
#include <linux/netfilter/xt_tcpudp.h>
#include <linux/netfilter/xt_comment.h>
#include <linux/netfilter/xt_conntrack.h>
#include <linux/netfilter/xt_mark.h>
#include <linux/netfilter/xt_addrtype.h>
#include <linux/netfilter/xt_pkttype.h>
#include <linux/netfilter/xt_rpfilter.h>
#include <linux/netfilter_ipv4/ip_tables.h>
#include <linux/netfilter_ipv4/ipt_REJECT.h>
#include <linux/netfilter_ipv6/ip6_tables.h>
#include <linux/netfilter_ipv6/ip6t_REJECT.h>
#include <stdlib.h>

int md_nft_compat_revision(unsigned family,const char *name,unsigned revision,unsigned target) {
    static const struct { const char *name; unsigned target,revisions; } extensions[]={
        {"MARK",1,1U<<2},{"mark",0,1U<<1},{"addrtype",0,1U<<1},{"conntrack",0,1U<<3},
        {"DNAT",1,7},{"SNAT",1,7},{"MASQUERADE",1,1},{"REJECT",1,1},
        {"tcp",0,1},{"udp",0,1},{"comment",0,1},{"pkttype",0,1},{"rpfilter",0,1},
        {"icmp",0,1},{"icmp6",0,1}};
    if ((family!=NFPROTO_IPV4 && family!=NFPROTO_IPV6) || !name) return -ENOENT;
    if ((!strcmp(name,"icmp") && family!=NFPROTO_IPV4)
            || (!strcmp(name,"icmp6") && family!=NFPROTO_IPV6)) return -ENOENT;
    if (family==NFPROTO_IPV6 && !revision && (!strcmp(name,"DNAT") || !strcmp(name,"SNAT"))) return -EPROTONOSUPPORT;
    for (size_t i=0;i<sizeof(extensions)/sizeof(*extensions);++i)
        if (target==extensions[i].target && !strcmp(name,extensions[i].name))
            return revision<32 && (extensions[i].revisions&(1U<<revision)) ? 0 : -EPROTONOSUPPORT;
    return -ENOENT;
}
int md_nft_compat_supported(unsigned family,const char *name,unsigned revision,unsigned target) {
    return !md_nft_compat_revision(family,name,revision,target);
}
static unsigned table_family(const struct md_network_filter *f,uint64_t table) {
    for (size_t i=0;i<f->count;++i) if (f->tables[i].handle==table) return f->tables[i].family;
    return NFPROTO_UNSPEC;
}
static int nat_range(const struct attribute *wire,unsigned family,unsigned revision,struct nf_nat_range *out) {
    int legacy=!revision && family==NFPROTO_IPV4;
    size_t size=legacy ? sizeof(struct nf_nat_ipv4_multi_range_compat)
        : !revision ? sizeof(*out)
        : revision==1 ? sizeof(*out) : sizeof(struct nf_nat_range2);
    /* Xtables pads every revision to the guest's 8-byte ABI alignment. */
    if (!wire->data || wire->flags || wire->size!=((size+7)&~(size_t)7)) return -EINVAL;
    if (legacy) {
        struct nf_nat_ipv4_multi_range_compat info;
        memcpy(&info,wire->data,sizeof(info));
        if (info.rangesize!=1) return -EOPNOTSUPP;
        const struct nf_nat_ipv4_range *r=&info.range[0];
        *out=(struct nf_nat_range){.flags=r->flags,.min_addr.ip=r->min_ip,.max_addr.ip=r->max_ip,
            .min_proto=r->min,.max_proto=r->max};
    } else if (revision<=1) memcpy(out,wire->data,sizeof(*out));
    else {
        struct nf_nat_range2 info;
        memcpy(&info,wire->data,sizeof(info));
        *out=(struct nf_nat_range){.flags=info.flags,.min_addr=info.min_addr,.max_addr=info.max_addr,
            .min_proto=info.min_proto,.max_proto=info.max_proto};
    }
    return 0;
}
static int target(struct md_network_filter *f,uint64_t table,const struct attribute *data,
        struct md_filter_expression *e) {
    struct attribute a[NFTA_TARGET_MAX+1];
    int error=nested(data,BIT(NFTA_TARGET_NAME)|BIT(NFTA_TARGET_REV)|BIT(NFTA_TARGET_INFO),a,NFTA_TARGET_MAX+1);
    const char *name=NULL; uint32_t revision=0;
    if (!error) error=string(&a[NFTA_TARGET_NAME],&name);
    if (!error) error=required_number(&a[NFTA_TARGET_REV],&revision);
    if (error) return error;
    if (!md_nft_compat_supported(table_family(f,table),name,revision,1)) return -EOPNOTSUPP;
    const struct attribute *wire=&a[NFTA_TARGET_INFO];
    if (!strcmp(name,"REJECT")) {
        unsigned mode;
        if (!wire->data || wire->flags || wire->size!=8) return -EINVAL;
        memcpy(&mode,wire->data,sizeof(mode));
        e->code=MD_FILTER_REJECT;
        if(table_family(f,table)==NFPROTO_IPV6) {
            static const signed char codes[]={0,1,2,3,4,-1,-1,5,6};
            if(mode==IP6T_TCP_RESET) e->key=MD_REJECT_TCP;
            else if(mode<sizeof(codes) && codes[mode]>=0) { e->key=MD_REJECT_ICMP6; e->aux=codes[mode]; }
            else return -EOPNOTSUPP;
        } else {
            static const signed char codes[]={0,1,2,3,-1,9,10,-1,13};
            if(mode==IPT_TCP_RESET) e->key=MD_REJECT_TCP;
            else if(mode<sizeof(codes) && codes[mode]>=0) { e->key=MD_REJECT_ICMP4; e->aux=codes[mode]; }
            else return -EOPNOTSUPP;
        }
        return 0;
    }
    if (!strcmp(name,"MARK")) {
        struct xt_mark_tginfo2 info;
        if (!wire->data || wire->flags || wire->size!=sizeof(info)) return -EINVAL;
        memcpy(&info,wire->data,sizeof(info));
        e->code=MD_FILTER_META_SET; e->key=NFT_META_MARK; e->constant=1;
        memcpy(e->value,&info.mark,4); memcpy(e->mask,&info.mask,4);
        return 0;
    }
    struct nf_nat_range range;
    unsigned family=table_family(f,table)==NFPROTO_IPV6 ? AF_INET6 : AF_INET;
    unsigned bytes=family==AF_INET6 ? 16 : 4;
    error=nat_range(wire,table_family(f,table),revision,&range);
    if (error) return error;
    const struct nf_nat_range *r=&range;
    if (r->flags&~(NF_NAT_RANGE_MAP_IPS|NF_NAT_RANGE_PROTO_SPECIFIED)) return -EOPNOTSUPP;
    if ((r->flags&NF_NAT_RANGE_PROTO_SPECIFIED) && ntohs(r->min_proto.all)>ntohs(r->max_proto.all)) return -EINVAL;
    if (!strcmp(name,"MASQUERADE")) {
        if ((r->flags&NF_NAT_RANGE_MAP_IPS) || !md_ip_unspecified(md_ip_load(family,&r->min_addr))
                || !md_ip_unspecified(md_ip_load(family,&r->max_addr))
                || (!(r->flags&NF_NAT_RANGE_PROTO_SPECIFIED) && (r->min_proto.all || r->max_proto.all))) return -EOPNOTSUPP;
        e->code=MD_FILTER_MASQUERADE; e->constant=1;
        e->aux=!!(r->flags&NF_NAT_RANGE_PROTO_SPECIFIED); e->port_range=e->aux;
        e->nat_port=r->min_proto.all; e->port_max=r->max_proto.all;
        return 0;
    }
    if (((r->flags&NF_NAT_RANGE_MAP_IPS) && memcmp(&r->min_addr,&r->max_addr,bytes))
            || (!strcmp(name,"DNAT") && (r->flags&NF_NAT_RANGE_PROTO_SPECIFIED)
                && r->min_proto.all!=r->max_proto.all)) return -EOPNOTSUPP;
    e->code=MD_FILTER_NAT; e->constant=1; e->family=family; e->operation=!strcmp(name,"DNAT") ? NFT_NAT_DNAT : NFT_NAT_SNAT;
    e->key=!!(r->flags&NF_NAT_RANGE_MAP_IPS); e->aux=!!(r->flags&NF_NAT_RANGE_PROTO_SPECIFIED);
    if (!e->key && !e->aux) return -EOPNOTSUPP;
    memcpy(e->value,&r->min_addr,bytes); e->nat_port=r->min_proto.all;
    e->port_range=e->aux; e->port_max=r->max_proto.all;
    return 0;
}
static int compat_match(struct md_network_filter *f,uint64_t table,const struct attribute *data,
        struct md_filter_expression *e) {
    struct attribute a[NFTA_MATCH_MAX+1];
    int error=nested(data,BIT(NFTA_MATCH_NAME)|BIT(NFTA_MATCH_REV)|BIT(NFTA_MATCH_INFO),a,NFTA_MATCH_MAX+1);
    const char *name=NULL; uint32_t revision=0;
    if (!error) error=string(&a[NFTA_MATCH_NAME],&name);
    if (!error) error=required_number(&a[NFTA_MATCH_REV],&revision);
    if (error) return error;
    if (!md_nft_compat_supported(table_family(f,table),name,revision,0)) return -EOPNOTSUPP;
    const struct attribute *wire=&a[NFTA_MATCH_INFO];
    if (!strcmp(name,"icmp") || !strcmp(name,"icmp6")) {
        _Static_assert(sizeof(struct ipt_icmp)==4 && sizeof(struct ip6t_icmp)==4,"ICMP match ABI");
        if (!wire->data || wire->flags || wire->size!=8) return -EINVAL;
        const unsigned char *info=wire->data;
        if (info[3]&~IPT_ICMP_INV) return -EINVAL;
        e->code=MD_FILTER_ICMP_MATCH;
        e->key=table_family(f,table)==NFPROTO_IPV4 ? IPPROTO_ICMP : IPPROTO_ICMPV6;
        memcpy(e->value,info,4);
        return 0;
    }
    if (!strcmp(name,"pkttype")) {
        struct xt_pkttype_info info;
        if (!wire->data || wire->flags || wire->size!=sizeof(info)) return -EINVAL;
        memcpy(&info,wire->data,sizeof(info));
        if (info.pkttype<0 || info.pkttype>255 || (info.invert!=0 && info.invert!=1)) return -EINVAL;
        e->code=MD_FILTER_PACKET_TYPE; e->key=(unsigned)info.pkttype; e->operation=(unsigned)info.invert;
        return 0;
    }
    if (!strcmp(name,"rpfilter")) {
        struct xt_rpfilter_info info;
        if (!wire->data || wire->flags || wire->size!=8) return -EINVAL;
        memcpy(&info,wire->data,sizeof(info));
        if (info.flags&~(XT_RPFILTER_LOOSE|XT_RPFILTER_VALID_MARK|XT_RPFILTER_ACCEPT_LOCAL|XT_RPFILTER_INVERT)) return -EINVAL;
        e->code=MD_FILTER_REVERSE_PATH; e->key=info.flags;
        return 0;
    }
    if (!strcmp(name,"addrtype")) {
        struct xt_addrtype_info_v1 info;
        if (!wire->data || wire->flags || wire->size!=sizeof(info)) return -EINVAL;
        memcpy(&info,wire->data,sizeof(info));
        if ((info.flags&~(XT_ADDRTYPE_INVERT_SOURCE|XT_ADDRTYPE_INVERT_DEST))
                || ((info.source|info.dest)&~(XT_ADDRTYPE_LOCAL|XT_ADDRTYPE_UNICAST))) return -EOPNOTSUPP;
        e->code=MD_FILTER_ADDRESS_TYPE; e->key=info.source; e->aux=info.dest;
        e->operation=(info.flags&XT_ADDRTYPE_INVERT_SOURCE ? MD_FILTER_INVERT_SOURCE : 0)
            |(info.flags&XT_ADDRTYPE_INVERT_DEST ? MD_FILTER_INVERT_DESTINATION : 0);
        return 0;
    }
    if (!strcmp(name,"mark")) {
        struct xt_mark_mtinfo1 info;
        if (!wire->data || wire->flags || wire->size!=((sizeof(info)+7)&~(size_t)7)) return -EINVAL;
        memcpy(&info,wire->data,sizeof(info));
        if (info.invert>1) return -EINVAL;
        e->code=MD_FILTER_MARK_MATCH; e->key=info.mark; e->aux=info.mask; e->operation=info.invert;
        return 0;
    }
    if (!strcmp(name,"comment")) {
        if (!wire->data || wire->flags || wire->size!=sizeof(struct xt_comment_info)) return -EINVAL;
        /* Comments always match; their bytes remain in the rule's wire view. */
        e->code=MD_FILTER_VERDICT; e->operation=NFT_CONTINUE;
        return 0;
    }
    if (!strcmp(name,"conntrack")) {
        struct xt_conntrack_mtinfo3 info;
        if (!wire->data || wire->flags || wire->size!=((sizeof(info)+7)&~(size_t)7)) return -EINVAL;
        memcpy(&info,wire->data,sizeof(info));
        /* The ordinary state bits share nft's conntrack-state encoding.
         * Tuple, status, timeout and NAT aliases need separate semantics. */
        if (info.match_flags!=XT_CONNTRACK_STATE || (info.invert_flags&~XT_CONNTRACK_STATE)
                || (info.state_mask&~15U)) return -EOPNOTSUPP;
        e->code=MD_FILTER_STATE_MATCH; e->key=info.state_mask;
        e->operation=!!(info.invert_flags&XT_CONNTRACK_STATE);
        return 0;
    }
    int tcp=!strcmp(name,"tcp");
    size_t size=tcp ? sizeof(struct xt_tcp) : sizeof(struct xt_udp);
    if (!wire->data || wire->flags || wire->size!=((size+7)&~(size_t)7)) return -EINVAL;
    struct md_filter_transport *t=&e->transport;
    unsigned inversion;
    if (tcp) {
        struct xt_tcp info; memcpy(&info,wire->data,sizeof(info));
        if (info.option || info.flg_mask || info.flg_cmp || (info.invflags&~(XT_TCP_INV_SRCPT|XT_TCP_INV_DSTPT))) return -EOPNOTSUPP;
        memcpy(t->source,info.spts,sizeof(t->source)); memcpy(t->destination,info.dpts,sizeof(t->destination));
        inversion=info.invflags;
    } else {
        struct xt_udp info; memcpy(&info,wire->data,sizeof(info));
        if (info.invflags&~(XT_UDP_INV_SRCPT|XT_UDP_INV_DSTPT)) return -EOPNOTSUPP;
        memcpy(t->source,info.spts,sizeof(t->source)); memcpy(t->destination,info.dpts,sizeof(t->destination));
        inversion=info.invflags;
    }
    if (t->source[0]>t->source[1] || t->destination[0]>t->destination[1]) return -EINVAL;
    t->protocol=tcp ? IPPROTO_TCP : IPPROTO_UDP;
    t->inversion=(inversion&XT_TCP_INV_SRCPT ? MD_FILTER_INVERT_SOURCE : 0)
        |(inversion&XT_TCP_INV_DSTPT ? MD_FILTER_INVERT_DESTINATION : 0);
    /* A transport predicate must not clobber nft data registers. */
    e->code=MD_FILTER_TRANSPORT;
    return 0;
}

static int data_value(const struct attribute *a,void *value,unsigned *size) {
    struct attribute attrs[NFTA_DATA_MAX+1];
    int error=nested(a,BIT(NFTA_DATA_VALUE),attrs,NFTA_DATA_MAX+1);
    if (error) return error;
    const struct attribute *v=&attrs[NFTA_DATA_VALUE];
    if (!v->data || v->flags || !v->size || v->size>16) return -EINVAL;
    memcpy(value,v->data,v->size); *size=(unsigned)v->size; return 0;
}
static int reg(const struct attribute *a,unsigned size,unsigned *offset,uint64_t *initialized,int write) {
    uint32_t id;
    int error=required_number(a,&id); if (error) return error;
    unsigned start=id>=NFT_REG_1 && id<=NFT_REG_4 ? (id-NFT_REG_1)*16
        : id>=NFT_REG32_00 && id<=NFT_REG32_15 ? (id-NFT_REG32_00)*4 : 64;
    if (!size || size>64 || start>64-size) return -EINVAL;
    unsigned covered=write ? (size+3)&~3U : size;
    uint64_t mask=(UINT64_MAX>>(64-covered))<<start;
    if (!write && (*initialized&mask)!=mask) return -EINVAL;
    if (write) *initialized|=mask;
    *offset=start; return 0;
}
int md_nft_verdict(struct md_network_filter *f,uint64_t table,const struct attribute *a,struct md_filter_verdict *out) {
    struct attribute b[NFTA_DATA_MAX+1],v[NFTA_VERDICT_MAX+1];
    int error=nested(a,BIT(NFTA_DATA_VERDICT),b,NFTA_DATA_MAX+1);
    if (!error) error=nested(&b[NFTA_DATA_VERDICT],BIT(NFTA_VERDICT_CODE)|BIT(NFTA_VERDICT_CHAIN)|BIT(NFTA_VERDICT_CHAIN_ID),v,NFTA_VERDICT_MAX+1);
    if (!error) error=required_number(&v[NFTA_VERDICT_CODE],&out->operation);
    if (error) return error;
    if ((int)out->operation==NFT_JUMP || (int)out->operation==NFT_GOTO) {
        const char *chain=NULL; uint64_t id=0;
        error=string(&v[NFTA_VERDICT_CHAIN],&chain);
        if (!error) error=number(&v[NFTA_VERDICT_CHAIN_ID],&id,0);
        if (error) return error;
        struct md_filter_chain *c=md_filter_chain_find(f,table,chain,0,(uint32_t)id);
        if (!c) return -ENOENT;
        if (c->hook>=0) return -EOPNOTSUPP;
        out->target=c->handle;
    } else {
        if (v[NFTA_VERDICT_CHAIN].data || v[NFTA_VERDICT_CHAIN_ID].data) return -EINVAL;
        if ((int)out->operation!=NFT_RETURN && (int)out->operation!=NFT_CONTINUE
                && (int)out->operation!=NFT_BREAK && out->operation!=NF_ACCEPT && out->operation!=NF_DROP) return -EOPNOTSUPP;
    }
    return 0;
}
int md_nft_emit_verdict(struct md_network_filter *f,uint64_t table,const struct md_filter_verdict *v,struct md_network_message *o) {
    size_t at=0;
    uint32_t code=htonl(v->operation);
    int error=md_network_message_nest(o,NFTA_DATA_VERDICT,&at);
    if (!error) error=md_network_message_attribute(o,NFTA_VERDICT_CODE,&code,sizeof(code));
    if (!error && ((int)v->operation==NFT_JUMP || (int)v->operation==NFT_GOTO)) {
        struct md_filter_chain *c=md_filter_chain_find(f,table,NULL,v->target,0);
        if (!c) return -ENOENT;
        error=md_network_message_attribute(o,NFTA_VERDICT_CHAIN,c->name,strlen(c->name)+1);
    }
    return error ? error : md_network_message_end_nest(o,at);
}
static int expression(struct md_network_filter *f,uint64_t table,
        const char *name,const struct attribute *data,struct md_filter_expression *e,uint64_t *initialized) {
    struct attribute a[32]; int error;
    uint32_t value=0;
#define FIELDS(mask) do { error=nested(data,(mask),a,32); if (error) return error; } while (0)
#define NUM(key,dst) do { error=required_number(&a[key],&(dst)); if (error) return error; } while (0)
#define REG(key,size,dst,write) do { error=reg(&a[key],size,&(dst),initialized,write); if (error) return error; } while (0)
    if (!strcmp(name,"reject")) {
        FIELDS(BIT(NFTA_REJECT_TYPE)|BIT(NFTA_REJECT_ICMP_CODE));
        NUM(NFTA_REJECT_TYPE,value);
        /* nft also serializes an unused one-byte ICMP code for TCP reset. */
        if (a[NFTA_REJECT_ICMP_CODE].data
                && (a[NFTA_REJECT_ICMP_CODE].flags || a[NFTA_REJECT_ICMP_CODE].size!=1)) return -EINVAL;
        e->code=MD_FILTER_REJECT;
        if(value==NFT_REJECT_TCP_RST) e->key=MD_REJECT_TCP;
        else {
            if(!a[NFTA_REJECT_ICMP_CODE].data) return -EINVAL;
            e->aux=*(const unsigned char *)a[NFTA_REJECT_ICMP_CODE].data;
            unsigned family=table_family(f,table);
            if(value==NFT_REJECT_ICMPX_UNREACH && family==NFPROTO_INET) e->key=MD_REJECT_ICMPX;
            else if(value==NFT_REJECT_ICMP_UNREACH && family==NFPROTO_IPV4) e->key=MD_REJECT_ICMP4;
            else if(value==NFT_REJECT_ICMP_UNREACH && family==NFPROTO_IPV6) e->key=MD_REJECT_ICMP6;
            else return -EOPNOTSUPP;
            if(!md_network_reject_valid(e->key,e->aux)) return -EOPNOTSUPP;
        }
    } else if (!strcmp(name,"immediate")) {
        FIELDS(BIT(NFTA_IMMEDIATE_DREG)|BIT(NFTA_IMMEDIATE_DATA));
        NUM(NFTA_IMMEDIATE_DREG,value);
        if (value==NFT_REG_VERDICT) {
            struct md_filter_verdict v={0};
            error=md_nft_verdict(f,table,&a[NFTA_IMMEDIATE_DATA],&v);
            if (error) return error;
            e->code=MD_FILTER_VERDICT; e->operation=v.operation; e->target=v.target;
        } else {
            e->code=MD_FILTER_IMMEDIATE;
            error=data_value(&a[NFTA_IMMEDIATE_DATA],e->value,&e->size); if (error) return error;
            REG(NFTA_IMMEDIATE_DREG,e->size,e->dst,1);
        }
    } else if (!strcmp(name,"meta")) {
        FIELDS(BIT(NFTA_META_KEY)|BIT(NFTA_META_DREG)|BIT(NFTA_META_SREG));
        NUM(NFTA_META_KEY,e->key);
        e->code=a[NFTA_META_SREG].data ? MD_FILTER_META_SET : MD_FILTER_META;
        e->size=(e->key==NFT_META_NFPROTO || e->key==NFT_META_L4PROTO || e->key==NFT_META_PKTTYPE) ? 1
            : (e->key==NFT_META_IIFNAME || e->key==NFT_META_OIFNAME) ? 16 : 4;
        if (e->code==MD_FILTER_META_SET) {
            if (e->key!=NFT_META_MARK || a[NFTA_META_DREG].data) return -EOPNOTSUPP;
            REG(NFTA_META_SREG,4,e->src,0);
        } else {
            if (e->key!=NFT_META_NFPROTO && e->key!=NFT_META_L4PROTO && e->key!=NFT_META_MARK
                    && e->key!=NFT_META_PKTTYPE
                    && e->key!=NFT_META_IIF && e->key!=NFT_META_OIF && e->key!=NFT_META_IIFNAME && e->key!=NFT_META_OIFNAME) return -EOPNOTSUPP;
            REG(NFTA_META_DREG,e->size,e->dst,1);
        }
    } else if (!strcmp(name,"payload")) {
        FIELDS(BIT(NFTA_PAYLOAD_DREG)|BIT(NFTA_PAYLOAD_BASE)|BIT(NFTA_PAYLOAD_OFFSET)|BIT(NFTA_PAYLOAD_LEN));
        e->code=MD_FILTER_PAYLOAD;
        NUM(NFTA_PAYLOAD_BASE,e->key); NUM(NFTA_PAYLOAD_OFFSET,e->aux); NUM(NFTA_PAYLOAD_LEN,e->size);
        if (e->key==NFT_PAYLOAD_NETWORK_HEADER) {
            unsigned family=table_family(f,table);
            int v4=(e->aux==9 && e->size==1) || (e->aux>=12 && e->aux<20 && e->size<=20-e->aux);
            int v6=(e->aux==6 && e->size==1) || (e->aux>=8 && e->aux<40 && e->size<=40-e->aux);
            if (!(family!=NFPROTO_IPV6 && v4) && !(family!=NFPROTO_IPV4 && v6)) return -EOPNOTSUPP;
        } else if (e->key!=NFT_PAYLOAD_TRANSPORT_HEADER || e->aux>=4 || e->size>4-e->aux) return -EOPNOTSUPP;
        REG(NFTA_PAYLOAD_DREG,e->size,e->dst,1);
    } else if (!strcmp(name,"cmp")) {
        FIELDS(BIT(NFTA_CMP_SREG)|BIT(NFTA_CMP_OP)|BIT(NFTA_CMP_DATA));
        e->code=MD_FILTER_COMPARE; NUM(NFTA_CMP_OP,e->operation);
        if (e->operation!=NFT_CMP_EQ && e->operation!=NFT_CMP_NEQ) return -EOPNOTSUPP;
        error=data_value(&a[NFTA_CMP_DATA],e->value,&e->size); if (error) return error;
        REG(NFTA_CMP_SREG,e->size,e->src,0);
    } else if (!strcmp(name,"range")) {
        FIELDS(BIT(NFTA_RANGE_SREG)|BIT(NFTA_RANGE_OP)|BIT(NFTA_RANGE_FROM_DATA)|BIT(NFTA_RANGE_TO_DATA));
        e->code=MD_FILTER_RANGE; NUM(NFTA_RANGE_OP,e->operation);
        if (e->operation!=NFT_RANGE_EQ && e->operation!=NFT_RANGE_NEQ) return -EOPNOTSUPP;
        error=data_value(&a[NFTA_RANGE_FROM_DATA],e->value,&e->size);
        if (!error) error=data_value(&a[NFTA_RANGE_TO_DATA],e->mask,&value);
        if (error) return error;
        if (value!=e->size || memcmp(e->value,e->mask,e->size)>0) return -EINVAL;
        REG(NFTA_RANGE_SREG,e->size,e->src,0);
    } else if (!strcmp(name,"bitwise")) {
        FIELDS(BIT(NFTA_BITWISE_SREG)|BIT(NFTA_BITWISE_DREG)|BIT(NFTA_BITWISE_LEN)|BIT(NFTA_BITWISE_MASK)|BIT(NFTA_BITWISE_XOR)|BIT(NFTA_BITWISE_OP));
        e->code=MD_FILTER_BITWISE; NUM(NFTA_BITWISE_LEN,e->size);
        uint64_t operation=0; error=number(&a[NFTA_BITWISE_OP],&operation,0);
        if (error) return error;
        if (operation!=NFT_BITWISE_BOOL) return -EOPNOTSUPP;
        error=data_value(&a[NFTA_BITWISE_MASK],e->mask,&value);
        if (error) return error;
        if (value!=e->size) return -EINVAL;
        error=data_value(&a[NFTA_BITWISE_XOR],e->value,&value);
        if (error) return error;
        if (value!=e->size) return -EINVAL;
        REG(NFTA_BITWISE_SREG,e->size,e->src,0); REG(NFTA_BITWISE_DREG,e->size,e->dst,1);
    } else if (!strcmp(name,"ct")) {
        FIELDS(BIT(NFTA_CT_KEY)|BIT(NFTA_CT_DREG)|BIT(NFTA_CT_SREG));
        NUM(NFTA_CT_KEY,e->key); e->size=4;
        e->code=a[NFTA_CT_SREG].data ? MD_FILTER_CT_SET : MD_FILTER_CT;
        if (e->code==MD_FILTER_CT_SET) {
            if (e->key!=NFT_CT_MARK || a[NFTA_CT_DREG].data) return -EOPNOTSUPP;
            REG(NFTA_CT_SREG,4,e->src,0);
        } else {
            if (e->key!=NFT_CT_STATE && e->key!=NFT_CT_MARK && e->key!=NFT_CT_STATUS) return -EOPNOTSUPP;
            REG(NFTA_CT_DREG,4,e->dst,1);
        }
    } else if (!strcmp(name,"fib")) {
        FIELDS(BIT(NFTA_FIB_DREG)|BIT(NFTA_FIB_RESULT)|BIT(NFTA_FIB_FLAGS));
        NUM(NFTA_FIB_RESULT,e->key); NUM(NFTA_FIB_FLAGS,e->operation);
        unsigned address=e->operation&(NFTA_FIB_F_DADDR|NFTA_FIB_F_SADDR);
        unsigned device=e->operation&(NFTA_FIB_F_IIF|NFTA_FIB_F_OIF);
        if ((address!=NFTA_FIB_F_DADDR && address!=NFTA_FIB_F_SADDR)
                || device==(NFTA_FIB_F_IIF|NFTA_FIB_F_OIF)
                || (e->operation&~(NFTA_FIB_F_SADDR|NFTA_FIB_F_DADDR|NFTA_FIB_F_IIF|NFTA_FIB_F_OIF|NFTA_FIB_F_MARK|NFTA_FIB_F_PRESENT))) return -EINVAL;
        if (e->key!=NFT_FIB_RESULT_ADDRTYPE && e->key!=NFT_FIB_RESULT_OIF && e->key!=NFT_FIB_RESULT_OIFNAME) return -EINVAL;
        if (e->key!=NFT_FIB_RESULT_ADDRTYPE && (e->operation&NFTA_FIB_F_OIF)) return -EINVAL;
        e->code=MD_FILTER_FIB; e->size=e->key==NFT_FIB_RESULT_OIFNAME ? 16 : 4;
        REG(NFTA_FIB_DREG,e->size,e->dst,1);
    } else if (!strcmp(name,"lookup")) {
        FIELDS(BIT(NFTA_LOOKUP_SET)|BIT(NFTA_LOOKUP_SET_ID)|BIT(NFTA_LOOKUP_SREG)|BIT(NFTA_LOOKUP_DREG)|BIT(NFTA_LOOKUP_FLAGS));
        const char *name=NULL; uint64_t id=0,flags=0;
        error=string(&a[NFTA_LOOKUP_SET],&name);
        if (!error) error=number(&a[NFTA_LOOKUP_SET_ID],&id,0);
        if (!error) error=number(&a[NFTA_LOOKUP_FLAGS],&flags,0);
        if (error) return error;
        if (flags&~NFT_LOOKUP_F_INV) return -EOPNOTSUPP;
        struct md_filter_set *s=md_nft_set(f,table,name,(uint32_t)id);
        if (!s) return -ENOENT;
        e->code=MD_FILTER_LOOKUP; e->target=s->handle; e->size=s->key_size; e->operation=flags;
        REG(NFTA_LOOKUP_SREG,e->size,e->src,0);
        if (s->flags&NFT_SET_MAP) {
            NUM(NFTA_LOOKUP_DREG,value);
            if (flags) return -EINVAL;
            if (s->data_size) {
                e->code=MD_FILTER_DATA_MAP; e->aux=s->data_size;
                REG(NFTA_LOOKUP_DREG,e->aux,e->dst,1);
            } else {
                if (value!=NFT_REG_VERDICT) return -EINVAL;
                e->code=MD_FILTER_VERDICT_MAP;
            }
        } else if (a[NFTA_LOOKUP_DREG].data) return -EINVAL;
    } else if (!strcmp(name,"nat")) {
        FIELDS(BIT(NFTA_NAT_TYPE)|BIT(NFTA_NAT_FAMILY)|BIT(NFTA_NAT_REG_ADDR_MIN)|BIT(NFTA_NAT_REG_ADDR_MAX)
            |BIT(NFTA_NAT_REG_PROTO_MIN)|BIT(NFTA_NAT_REG_PROTO_MAX)|BIT(NFTA_NAT_FLAGS));
        NUM(NFTA_NAT_TYPE,e->operation); NUM(NFTA_NAT_FAMILY,value);
        uint64_t flags=0; error=number(&a[NFTA_NAT_FLAGS],&flags,0);
        if (error) return error;
        if ((value!=NFPROTO_IPV4 && value!=NFPROTO_IPV6) || e->operation>NFT_NAT_DNAT
                || (flags&~(NF_NAT_RANGE_MAP_IPS|NF_NAT_RANGE_PROTO_SPECIFIED))) return -EOPNOTSUPP;
        unsigned family=table_family(f,table);
        if (family!=NFPROTO_INET && family!=value) return -EAFNOSUPPORT;
        e->code=MD_FILTER_NAT; e->family=value==NFPROTO_IPV6 ? AF_INET6 : AF_INET;
        unsigned bytes=e->family==AF_INET6 ? 16 : 4;
        if (a[NFTA_NAT_REG_ADDR_MIN].data) {
            REG(NFTA_NAT_REG_ADDR_MIN,bytes,e->dst,0); e->key=1;
            if (a[NFTA_NAT_REG_ADDR_MAX].data) { unsigned other; REG(NFTA_NAT_REG_ADDR_MAX,bytes,other,0); if (other!=e->dst) return -EOPNOTSUPP; }
        } else if (a[NFTA_NAT_REG_ADDR_MAX].data) return -EINVAL;
        if (a[NFTA_NAT_REG_PROTO_MIN].data) {
            REG(NFTA_NAT_REG_PROTO_MIN,2,e->src,0); e->aux=1;
            if (a[NFTA_NAT_REG_PROTO_MAX].data) {
                REG(NFTA_NAT_REG_PROTO_MAX,2,e->port_max,0); e->port_range=1;
                if (e->operation==NFT_NAT_DNAT && e->port_max!=e->src) return -EOPNOTSUPP;
            }
        } else if (a[NFTA_NAT_REG_PROTO_MAX].data) return -EINVAL;
        if (!e->key && !e->aux) return -EINVAL;
        if (((flags&NF_NAT_RANGE_MAP_IPS) && !e->key)
                || ((flags&NF_NAT_RANGE_PROTO_SPECIFIED) && !e->aux)) return -EINVAL;
    } else if (!strcmp(name,"counter")) {
        FIELDS(BIT(NFTA_COUNTER_PACKETS)|BIT(NFTA_COUNTER_BYTES));
        e->code=MD_FILTER_COUNTER;
        error=number(&a[NFTA_COUNTER_PACKETS],&e->counter.packets,1);
        if (!error) error=number(&a[NFTA_COUNTER_BYTES],&e->counter.bytes,1);
        if (error) return error;
    } else if (!strcmp(name,"masq")) {
        FIELDS(BIT(NFTA_MASQ_FLAGS)|BIT(NFTA_MASQ_REG_PROTO_MIN)|BIT(NFTA_MASQ_REG_PROTO_MAX)); uint64_t flags=0;
        error=number(&a[NFTA_MASQ_FLAGS],&flags,0); if (error) return error;
        if (flags&~NF_NAT_RANGE_PROTO_SPECIFIED) return -EOPNOTSUPP;
        e->code=MD_FILTER_MASQUERADE;
        if (a[NFTA_MASQ_REG_PROTO_MIN].data) {
            REG(NFTA_MASQ_REG_PROTO_MIN,2,e->src,0); e->aux=1;
            if (a[NFTA_MASQ_REG_PROTO_MAX].data) {
                REG(NFTA_MASQ_REG_PROTO_MAX,2,e->port_max,0); e->port_range=1;
            }
        } else if (a[NFTA_MASQ_REG_PROTO_MAX].data || flags) return -EINVAL;
    } else return -EOPNOTSUPP;
    return 0;
#undef FIELDS
#undef NUM
#undef REG
}
int md_nft_expressions(struct md_network_filter *f,uint64_t table,struct md_filter_rule *r,const void *data,size_t size) {
    uint64_t initialized=0;
    struct md_netlink_cursor cursor={data,size};
    struct md_netlink_attribute item; int result;
    while ((result=md_netlink_attribute_next(&cursor,&item))>0) {
        if (item.type!=NFTA_LIST_ELEM || (item.flags&~NLA_F_NESTED)) return -EINVAL;
        struct attribute a[NFTA_EXPR_MAX+1];
        int error=fields(item.data,item.size,BIT(NFTA_EXPR_NAME)|BIT(NFTA_EXPR_DATA),a,NFTA_EXPR_MAX+1);
        const char *name=NULL;
        if (!error) error=string(&a[NFTA_EXPR_NAME],&name);
        if (error) return error;
        if (!name || r->count==64) return -E2BIG;
        /* An omitted expression payload is an empty attribute set. Each
         * expression still validates its own required parameters. */
        if (!a[NFTA_EXPR_DATA].data)
            a[NFTA_EXPR_DATA]=(struct attribute){.data=item.data};
        struct md_filter_expression *e=&r->expressions[r->count];
        error=!strcmp(name,"target") ? target(f,table,&a[NFTA_EXPR_DATA],e)
            : !strcmp(name,"match") ? compat_match(f,table,&a[NFTA_EXPR_DATA],e)
            : expression(f,table,name,&a[NFTA_EXPR_DATA],e,&initialized);
        if (error) return error;
        ++r->count;
    }
    if (result<0) return result;
    if (size) {
        r->wire=malloc(size); if (!r->wire) return -ENOMEM;
        memcpy(r->wire,data,size); r->wire_size=size;
    }
    return 0;
}
static int u32(struct md_network_message *o,unsigned type,uint32_t value) {
    value=htonl(value); return md_network_message_attribute(o,type,&value,4);
}
static unsigned wire_register(unsigned offset) { return NFT_REG32_00+offset/4; }
static int emit_value(struct md_network_message *o,unsigned type,const void *value,unsigned size) {
    size_t nest=0;
    int error=md_network_message_nest(o,type,&nest);
    if (!error) error=md_network_message_attribute(o,NFTA_DATA_VALUE,value,size);
    if (!error) error=md_network_message_end_nest(o,nest);
    return error;
}
/* Launch policies build typed expressions directly. Keep their nft dump
 * round-trippable without manufacturing a netlink request to configure them. */
static int emit_typed(const struct md_filter_expression *e,struct md_network_message *o) {
    const char *name;
    switch (e->code) {
    case MD_FILTER_IMMEDIATE: name="immediate"; break;
    case MD_FILTER_META: name="meta"; break;
    case MD_FILTER_PAYLOAD: name="payload"; break;
    case MD_FILTER_COMPARE: name="cmp"; break;
    case MD_FILTER_NAT: name="nat"; break;
    case MD_FILTER_MASQUERADE: name="masq"; break;
    default: return -EOPNOTSUPP;
    }
    size_t entry=0,data=0;
    int error=md_network_message_nest(o,NFTA_LIST_ELEM,&entry);
#define PUT(call) do { if (!error) error=(call); } while (0)
    PUT(md_network_message_attribute(o,NFTA_EXPR_NAME,name,strlen(name)+1));
    PUT(md_network_message_nest(o,NFTA_EXPR_DATA,&data));
    switch (e->code) {
    case MD_FILTER_IMMEDIATE:
        PUT(u32(o,NFTA_IMMEDIATE_DREG,wire_register(e->dst)));
        PUT(emit_value(o,NFTA_IMMEDIATE_DATA,e->value,e->size)); break;
    case MD_FILTER_META:
        PUT(u32(o,NFTA_META_KEY,e->key)); PUT(u32(o,NFTA_META_DREG,wire_register(e->dst))); break;
    case MD_FILTER_PAYLOAD:
        PUT(u32(o,NFTA_PAYLOAD_BASE,e->key)); PUT(u32(o,NFTA_PAYLOAD_OFFSET,e->aux));
        PUT(u32(o,NFTA_PAYLOAD_LEN,e->size)); PUT(u32(o,NFTA_PAYLOAD_DREG,wire_register(e->dst))); break;
    case MD_FILTER_COMPARE:
        PUT(u32(o,NFTA_CMP_SREG,wire_register(e->src))); PUT(u32(o,NFTA_CMP_OP,e->operation));
        PUT(emit_value(o,NFTA_CMP_DATA,e->value,e->size)); break;
    case MD_FILTER_NAT:
        PUT(u32(o,NFTA_NAT_TYPE,e->operation)); PUT(u32(o,NFTA_NAT_FAMILY,e->family==AF_INET6 ? NFPROTO_IPV6 : NFPROTO_IPV4));
        if (e->key) PUT(u32(o,NFTA_NAT_REG_ADDR_MIN,wire_register(e->dst)));
        if (e->aux) PUT(u32(o,NFTA_NAT_REG_PROTO_MIN,wire_register(e->src)));
        if (e->port_range) PUT(u32(o,NFTA_NAT_REG_PROTO_MAX,wire_register(e->port_max)));
        break;
    case MD_FILTER_MASQUERADE:
        if (e->aux) PUT(u32(o,NFTA_MASQ_REG_PROTO_MIN,wire_register(e->src)));
        if (e->port_range) PUT(u32(o,NFTA_MASQ_REG_PROTO_MAX,wire_register(e->port_max)));
        break;
    default: break;
    }
    PUT(md_network_message_end_nest(o,data)); PUT(md_network_message_end_nest(o,entry));
    return error;
#undef PUT
}
int md_nft_emit_expressions(struct md_network_filter *f,const struct md_filter_rule *r,struct md_network_message *o) {
    size_t list=0,offset=0;
    int error=md_network_message_nest(o,NFTA_RULE_EXPRESSIONS,&list);
#define PUT(call) do { if (!error) error=(call); } while (0)
    for (unsigned i=0;!error && i<r->count;++i) {
        const struct md_filter_expression *e=&r->expressions[i];
        if (!r->wire) { error=emit_typed(e,o); continue; }
        const struct nlattr *item=(const void *)((const char *)r->wire+offset);
        struct attribute a[NFTA_EXPR_MAX+1];
        error=fields(item+1,item->nla_len-sizeof(*item),BIT(NFTA_EXPR_NAME)|BIT(NFTA_EXPR_DATA),a,NFTA_EXPR_MAX+1);
        size_t entry=0,data=0;
        PUT(md_network_message_nest(o,NFTA_LIST_ELEM,&entry));
        PUT(md_network_message_attribute(o,NFTA_EXPR_NAME,a[NFTA_EXPR_NAME].data,a[NFTA_EXPR_NAME].size));
        if (e->code==MD_FILTER_COUNTER) {
            uint64_t packets=htobe64(e->counter.packets),bytes=htobe64(e->counter.bytes);
            PUT(md_network_message_nest(o,NFTA_EXPR_DATA,&data));
            PUT(md_network_message_attribute(o,NFTA_COUNTER_PACKETS,&packets,sizeof(packets)));
            PUT(md_network_message_attribute(o,NFTA_COUNTER_BYTES,&bytes,sizeof(bytes)));
            PUT(md_network_message_end_nest(o,data));
        } else if (md_filter_set_expression(e)) {
            struct md_filter_set *s=md_filter_set_find(f,0,NULL,e->target,0);
            if (!s) return -ENOENT;
            PUT(md_network_message_nest(o,NFTA_EXPR_DATA,&data));
            PUT(md_network_message_attribute(o,NFTA_LOOKUP_SET,s->name,strlen(s->name)+1));
            PUT(u32(o,NFTA_LOOKUP_SREG,wire_register(e->src)));
            if (e->code==MD_FILTER_VERDICT_MAP) PUT(u32(o,NFTA_LOOKUP_DREG,NFT_REG_VERDICT));
            if (e->code==MD_FILTER_DATA_MAP) PUT(u32(o,NFTA_LOOKUP_DREG,wire_register(e->dst)));
            if (e->operation) PUT(u32(o,NFTA_LOOKUP_FLAGS,e->operation));
            PUT(md_network_message_end_nest(o,data));
        } else if (e->code==MD_FILTER_VERDICT && ((int)e->operation==NFT_JUMP || (int)e->operation==NFT_GOTO)) {
            struct md_filter_chain *c=md_filter_chain_find(f,0,NULL,e->target,0);
            if (!c) return -ENOENT;
            size_t value=0,verdict=0;
            PUT(md_network_message_nest(o,NFTA_EXPR_DATA,&data));
            PUT(u32(o,NFTA_IMMEDIATE_DREG,NFT_REG_VERDICT));
            PUT(md_network_message_nest(o,NFTA_IMMEDIATE_DATA,&value));
            PUT(md_network_message_nest(o,NFTA_DATA_VERDICT,&verdict));
            PUT(u32(o,NFTA_VERDICT_CODE,e->operation));
            PUT(md_network_message_attribute(o,NFTA_VERDICT_CHAIN,c->name,strlen(c->name)+1));
            PUT(md_network_message_end_nest(o,verdict)); PUT(md_network_message_end_nest(o,value));
            PUT(md_network_message_end_nest(o,data));
        } else PUT(md_network_message_attribute(o,NFTA_EXPR_DATA|NLA_F_NESTED,a[NFTA_EXPR_DATA].data,a[NFTA_EXPR_DATA].size));
        PUT(md_network_message_end_nest(o,entry)); offset+=NLA_ALIGN(item->nla_len);
    }
    PUT(md_network_message_end_nest(o,list)); return error;
#undef PUT
}
