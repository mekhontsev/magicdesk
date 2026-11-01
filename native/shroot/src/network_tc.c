#include "network_tc.h"
#include "network_internal.h"
#include "netlink_attributes.h"
#include <arpa/inet.h>
#include <errno.h>
#include <linux/if_ether.h>
#include <linux/pkt_cls.h>
#include <linux/pkt_sched.h>
#include <linux/rtnetlink.h>
#include <linux/tc_act/tc_csum.h>
#include <stdlib.h>
#include <string.h>

enum { MAX_FILTERS=32, MAX_KEYS=8 };
struct selector {
    /* tc_u32_sel's flexible tail is serialized separately. */
    unsigned count;
    struct tc_u32_key keys[MAX_KEYS];
};
struct filter {
    uint32_t handle, info, flags;
    struct selector selector;
    struct tc_csum action;
};
struct md_network_tc {
    unsigned references, count;
    uint32_t handle, direct_qlen;
    struct tc_htb_glob htb;
    struct filter filters[MAX_FILTERS];
};
struct md_network_tc *md_network_tc_retain(struct md_network_tc *tc) {
    if (tc) ++tc->references;
    return tc;
}
void md_network_tc_release(struct md_network_tc *tc) { if (tc && !--tc->references) free(tc); }
int md_network_tc_request(unsigned type) {
    switch (type) {
    case RTM_NEWQDISC: case RTM_DELQDISC: case RTM_GETQDISC:
    case RTM_NEWTCLASS: case RTM_DELTCLASS: case RTM_GETTCLASS:
    case RTM_NEWTFILTER: case RTM_DELTFILTER: case RTM_GETTFILTER: return 1;
    default: return 0;
    }
}
static int attrs(const void *data,size_t size,struct md_netlink_attribute *v,unsigned max,uint64_t allowed) {
    struct md_netlink_cursor cursor={data,size};
    struct md_netlink_attribute a; int r;
    while ((r=md_netlink_attribute_next(&cursor,&a))>0) {
        if (!a.type || a.type>max || !(allowed&(UINT64_C(1)<<a.type))) return -EOPNOTSUPP;
        if (a.flags&~NLA_F_NESTED || v[a.type].data) return -EINVAL;
        v[a.type]=a;
    }
    return r;
}
#define BIT(n) (UINT64_C(1)<<(n))
static int value(struct md_netlink_attribute a,void *out,size_t size) {
    if (!a.data || a.size!=size) return -EINVAL;
    memcpy(out,a.data,size); return 0;
}
static int kind(struct md_netlink_attribute a,const char *name) {
    return a.data && a.size==strlen(name)+1 && !memcmp(a.data,name,a.size);
}
static int parse_action(struct md_netlink_attribute a,struct tc_csum *out) {
    struct md_netlink_attribute list[2]={0},act[TCA_ACT_MAX+1]={0},opt[TCA_CSUM_MAX+1]={0};
    int r=attrs(a.data,a.size,list,1,BIT(1));
    if (r || !list[1].data) return r ? r : -EINVAL;
    r=attrs(list[1].data,list[1].size,act,TCA_ACT_MAX,BIT(TCA_ACT_KIND)|BIT(TCA_ACT_OPTIONS));
    if (r || !kind(act[TCA_ACT_KIND],"csum")) return r ? r : -EOPNOTSUPP;
    r=attrs(act[TCA_ACT_OPTIONS].data,act[TCA_ACT_OPTIONS].size,opt,TCA_CSUM_MAX,BIT(TCA_CSUM_PARMS));
    if (!r) r=value(opt[TCA_CSUM_PARMS],out,sizeof(*out));
    if (r) return r;
    if (out->index || out->capab || out->refcnt || out->bindcnt
            || (out->action!=TC_ACT_PIPE && out->action!=TC_ACT_OK)
            || (out->update_flags&~(TCA_CSUM_UPDATE_FLAG_IPV4HDR|TCA_CSUM_UPDATE_FLAG_UDP))) return -EOPNOTSUPP;
    return 0;
}
static int parse_filter(struct md_netlink_attribute options,struct filter *f) {
    struct md_netlink_attribute v[TCA_U32_MAX+1]={0};
    int r=attrs(options.data,options.size,v,TCA_U32_MAX,BIT(TCA_U32_SEL)|BIT(TCA_U32_ACT)|BIT(TCA_U32_FLAGS));
    if (r) return r;
    const struct md_netlink_attribute a=v[TCA_U32_SEL];
    struct tc_u32_sel sel;
    if (!a.data || a.size<sizeof(sel)) return -EINVAL;
    memcpy(&sel,a.data,sizeof(sel));
    if (sel.nkeys>MAX_KEYS) return -E2BIG;
    if (a.size!=sizeof(sel)+sel.nkeys*sizeof(struct tc_u32_key)) return -EINVAL;
    if (sel.flags!=TC_U32_TERMINAL || sel.offshift || sel.offmask || sel.off || sel.offoff || sel.hoff || sel.hmask)
        return -EOPNOTSUPP;
    f->selector.count=sel.nkeys;
    memcpy(f->selector.keys,(const char *)a.data+sizeof(sel),sel.nkeys*sizeof(struct tc_u32_key));
    for (unsigned i=0;i<sel.nkeys;++i) if (f->selector.keys[i].offmask) return -EOPNOTSUPP;
    if (v[TCA_U32_FLAGS].data) r=value(v[TCA_U32_FLAGS],&f->flags,sizeof(f->flags));
    if (r || (f->flags&~TCA_CLS_FLAGS_SKIP_HW)) return r ? r : -EOPNOTSUPP;
    return parse_action(v[TCA_U32_ACT],&f->action);
}
static int reply(struct md_network_message *o,const struct nlmsghdr *h,uint32_t port,int multi,
        unsigned index,const struct md_network_tc *tc,const struct filter *f,unsigned type) {
    struct tcmsg m={.tcm_ifindex=(int)index,.tcm_parent=f ? tc->handle : TC_H_ROOT,
        .tcm_handle=f ? f->handle : tc->handle,.tcm_info=f ? f->info : 0};
    int r=md_network_message_begin(o,h,port,type,multi,&m,sizeof(m));
    if (!r) r=md_network_message_attribute(o,TCA_KIND,f ? "u32" : "htb",4);
    size_t options=0,actions=0,action=0,params=0;
    if (!r) r=md_network_message_nest(o,TCA_OPTIONS,&options);
    if (!f) {
        struct tc_htb_glob htb=tc->htb;
        htb.version=TC_HTB_PROTOVER<<16;
        if (!r) r=md_network_message_attribute(o,TCA_HTB_INIT,&htb,sizeof(htb));
        if (!r) r=md_network_message_attribute(o,TCA_HTB_DIRECT_QLEN,&tc->direct_qlen,sizeof(tc->direct_qlen));
    } else {
        unsigned char bytes[sizeof(struct tc_u32_sel)+MAX_KEYS*sizeof(struct tc_u32_key)]={0};
        struct tc_u32_sel sel={.flags=TC_U32_TERMINAL,.nkeys=(unsigned char)f->selector.count};
        memcpy(bytes,&sel,sizeof(sel)); memcpy(bytes+sizeof(sel),f->selector.keys,f->selector.count*sizeof(struct tc_u32_key));
        if (!r) r=md_network_message_attribute(o,TCA_U32_SEL,bytes,sizeof(sel)+f->selector.count*sizeof(struct tc_u32_key));
        uint32_t hash=TC_U32_HTID(f->handle),flags=f->flags|TCA_CLS_FLAGS_NOT_IN_HW;
        if (!r) r=md_network_message_attribute(o,TCA_U32_HASH,&hash,sizeof(hash));
        if (!r) r=md_network_message_attribute(o,TCA_U32_FLAGS,&flags,sizeof(flags));
        if (!r) r=md_network_message_nest(o,TCA_U32_ACT,&actions);
        if (!r) r=md_network_message_nest(o,1,&action);
        if (!r) r=md_network_message_attribute(o,TCA_ACT_KIND,"csum",5);
        if (!r) r=md_network_message_nest(o,TCA_ACT_OPTIONS,&params);
        if (!r) r=md_network_message_attribute(o,TCA_CSUM_PARMS,&f->action,sizeof(f->action));
        if (!r) r=md_network_message_end_nest(o,params);
        if (!r) r=md_network_message_end_nest(o,action);
        if (!r) r=md_network_message_end_nest(o,actions);
    }
    if (!r) r=md_network_message_end_nest(o,options);
    return r;
}
static int selected(const struct tcmsg *m,const struct filter *f) {
    return (!m->tcm_handle || m->tcm_handle==f->handle)
        && (!TC_H_MAJ(m->tcm_info) || TC_H_MAJ(m->tcm_info)==TC_H_MAJ(f->info))
        && (!TC_H_MIN(m->tcm_info) || TC_H_MIN(m->tcm_info)==TC_H_MIN(f->info));
}
static int filter_order(const void *left,const void *right) {
    const struct filter *a=left,*b=right;
    if (a->info!=b->info) return a->info<b->info ? -1 : 1;
    return a->handle<b->handle ? -1 : a->handle!=b->handle;
}
int md_network_tc_control(struct md_network_namespace *n,const struct nlmsghdr *h,uint32_t port,int dump,
        struct md_network_message *out) {
    if (h->nlmsg_len<NLMSG_LENGTH(sizeof(struct tcmsg))) return -EINVAL;
    struct tcmsg m; memcpy(&m,NLMSG_DATA(h),sizeof(m));
    if (m.tcm_family!=AF_UNSPEC || m.tcm__pad1 || m.tcm__pad2 || m.tcm_ifindex<0) return -EINVAL;
    int filter=h->nlmsg_type>=RTM_NEWTFILTER;
    int classes=h->nlmsg_type>=RTM_NEWTCLASS && h->nlmsg_type<=RTM_GETTCLASS;
    int query=h->nlmsg_type==RTM_GETQDISC || h->nlmsg_type==RTM_GETTFILTER || h->nlmsg_type==RTM_GETTCLASS;
    int remove=h->nlmsg_type==RTM_DELQDISC || h->nlmsg_type==RTM_DELTFILTER;
    struct md_netlink_attribute v[TCA_MAX+1]={0};
    int r=attrs((const char *)NLMSG_DATA(h)+NLMSG_ALIGN(sizeof(m)),h->nlmsg_len-NLMSG_LENGTH(sizeof(m)),
        v,TCA_MAX,BIT(TCA_KIND)|BIT(TCA_OPTIONS)|BIT(TCA_CHAIN));
    if (r) return r;
    if (v[TCA_CHAIN].data) {
        uint32_t chain=0; r=value(v[TCA_CHAIN],&chain,sizeof(chain));
        if (r || chain || !filter) return r ? r : -EOPNOTSUPP;
    }
    if (v[TCA_KIND].data && !kind(v[TCA_KIND],filter ? "u32" : "htb")) return -EOPNOTSUPP;
    if (classes && (!query || !dump)) return -EOPNOTSUPP;
    struct md_network_interface *link=m.tcm_ifindex ? md_network_interface_find(n,(unsigned)m.tcm_ifindex,NULL) : NULL;
    if (m.tcm_ifindex && !link) return -ENODEV;
    if (query) {
        if (v[TCA_OPTIONS].data) return -EOPNOTSUPP;
        if (classes) return 0;
        unsigned found=0;
        for (unsigned i=0;i<n->count;++i) {
            const struct md_network_tc *tc=n->links[i].tc;
            if (!tc || (link && link!=&n->links[i])) continue;
            if (m.tcm_parent && m.tcm_parent!=(filter ? tc->handle : TC_H_ROOT)) continue;
            if (filter) {
                for (unsigned j=0;j<tc->count;++j) if (selected(&m,&tc->filters[j])) {
                    ++found; r=reply(out,h,port,dump,n->links[i].info.index,tc,&tc->filters[j],RTM_NEWTFILTER);
                    if (r) return r;
                }
            } else if (!m.tcm_handle || m.tcm_handle==tc->handle) {
                ++found; r=reply(out,h,port,dump,n->links[i].info.index,tc,NULL,RTM_NEWQDISC);
                if (r) return r;
            }
        }
        return found || dump ? 0 : -ENOENT;
    }
    if (!link) return -ENODEV;
    if (h->nlmsg_flags&NLM_F_ECHO) return -EOPNOTSUPP;
    if (link->kind==MD_LINK_TUN || link->kind==MD_LINK_UPLINK) return -EOPNOTSUPP;
    const struct md_network_tc *old=link->tc;
    if (filter ? !old || m.tcm_parent!=old->handle : m.tcm_parent!=TC_H_ROOT) return -EINVAL;
    if (!filter && (TC_H_MIN(m.tcm_handle) || m.tcm_info)) return -EINVAL;
    if (remove && !old) return -ENOENT;
    if (!filter && old && m.tcm_handle && m.tcm_handle!=old->handle) return -EOPNOTSUPP;
    if (!filter && remove) { md_network_tc_release(link->tc); link->tc=NULL; return 0; }
    if (!filter && old && (h->nlmsg_flags&NLM_F_EXCL)) return -EEXIST;
    if (!filter && !old && !(h->nlmsg_flags&NLM_F_CREATE)) return -ENOENT;
    struct md_network_tc *next=calloc(1,sizeof(*next));
    if (!next) return -ENOMEM;
    if (old) *next=*old;
    next->references=1;
    if (!filter) {
        struct md_netlink_attribute opt[TCA_HTB_MAX+1]={0};
        r=attrs(v[TCA_OPTIONS].data,v[TCA_OPTIONS].size,opt,TCA_HTB_MAX,BIT(TCA_HTB_INIT)|BIT(TCA_HTB_DIRECT_QLEN));
        if (!r) r=value(opt[TCA_HTB_INIT],&next->htb,sizeof(next->htb));
        if (!r && (next->htb.version!=TC_HTB_PROTOVER || !next->htb.rate2quantum
                || next->htb.defcls>0xffff || next->htb.debug || next->htb.direct_pkts)) r=-EOPNOTSUPP;
        if (!r && old) next->htb.direct_pkts=old->htb.direct_pkts;
        next->handle=m.tcm_handle ? m.tcm_handle : old ? old->handle : 0x10000;
        if (!old) next->direct_qlen=link->info.tx_queue_length;
        if (!r && opt[TCA_HTB_DIRECT_QLEN].data) r=value(opt[TCA_HTB_DIRECT_QLEN],&next->direct_qlen,4);
    } else if (remove) {
        unsigned removed=0;
        for (unsigned i=0;i<next->count;) {
            if (!selected(&m,&next->filters[i])) { ++i; continue; }
            memmove(&next->filters[i],&next->filters[i+1],(--next->count-i)*sizeof(next->filters[0])); ++removed;
        }
        if (!removed) r=-ENOENT;
    } else {
        struct filter f={.handle=m.tcm_handle,.info=m.tcm_info};
        if (!TC_H_MAJ(f.info) || TC_H_MIN(f.info)!=htons(ETH_P_IP)) r=-EOPNOTSUPP;
        if (!r) r=parse_filter(v[TCA_OPTIONS],&f);
        unsigned at=0;
        while (at<next->count && (!f.handle || next->filters[at].handle!=f.handle || next->filters[at].info!=f.info)) ++at;
        if (!r && at<next->count && (h->nlmsg_flags&NLM_F_EXCL)) r=-EEXIST;
        if (!r && at==next->count && !(h->nlmsg_flags&NLM_F_CREATE)) r=-ENOENT;
        if (!r && at==next->count && next->count==MAX_FILTERS) r=-ENOSPC;
        if (!r && f.handle && (TC_U32_HTID(f.handle)!=0x80000000 || TC_U32_HASH(f.handle) || !TC_U32_NODE(f.handle))) r=-EOPNOTSUPP;
        if (!r && !f.handle) {
            f.handle=0x80000800;
            for (;;) {
                unsigned j=0;
                while (j<next->count && (next->filters[j].handle!=f.handle || next->filters[j].info!=f.info)) ++j;
                if (j==next->count) break;
                ++f.handle;
            }
        }
        if (!r) {
            next->filters[at]=f;
            if (at==next->count) ++next->count;
            qsort(next->filters,next->count,sizeof(f),filter_order);
        }
    }
    if (r) { free(next); return r; }
    link->tc=next; md_network_tc_release((struct md_network_tc *)old);
    return 0;
}
static unsigned announce(struct md_network_namespace *n,unsigned index,const struct md_network_tc *tc,
        const struct filter *f,unsigned type) {
    union { struct nlmsghdr align; unsigned char data[1024]; } buffer;
    struct md_network_message out={.data=buffer.data,.capacity=sizeof(buffer)};
    struct nlmsghdr h={0};
    return reply(&out,&h,0,0,index,tc,f,type) ? 0 : md_network_broadcast(n,RTNLGRP_TC,out.data,out.size);
}
unsigned md_network_tc_notify(struct md_network_namespace *n,unsigned index,
        const struct md_network_tc *old,const struct md_network_tc *now) {
    if (old==now) return 0;
    unsigned published=0;
    if (old && !now) published+=announce(n,index,old,NULL,RTM_DELQDISC);
    if (now && (!old || old->handle!=now->handle || old->direct_qlen!=now->direct_qlen
            || memcmp(&old->htb,&now->htb,sizeof(now->htb)))) published+=announce(n,index,now,NULL,RTM_NEWQDISC);
    for (unsigned pass=0;pass<2;++pass) {
        const struct md_network_tc *from=pass ? now : old,*to=pass ? old : now;
        for (unsigned i=0;from && i<from->count;++i) {
            const struct filter *f=&from->filters[i]; unsigned j=0;
            while (to && j<to->count && (to->filters[j].handle!=f->handle || to->filters[j].info!=f->info)) ++j;
            if (to && j<to->count && (!pass || !memcmp(&to->filters[j],f,sizeof(*f)))) continue;
            published+=announce(n,index,from,f,pass ? RTM_NEWTFILTER : RTM_DELTFILTER);
        }
    }
    return published;
}
static unsigned be16(const unsigned char *p) { return ((unsigned)p[0]<<8)|p[1]; }
static void put16(unsigned char *p,unsigned v) { p[0]=(unsigned char)(v>>8); p[1]=(unsigned char)v; }
static uint32_t sum(const unsigned char *p,size_t size,uint32_t total) {
    while (size>=2) { total+=be16(p); p+=2; size-=2; }
    return size ? total+((unsigned)*p<<8) : total;
}
static unsigned folded(uint32_t total) {
    while (total>>16) total=(total&0xffff)+(total>>16);
    return (~total)&0xffff;
}
static int matches(const struct filter *f,const unsigned char *frame,size_t size) {
    for (unsigned i=0;i<f->selector.count;++i) {
        const struct tc_u32_key *key=&f->selector.keys[i];
        int64_t offset=ETH_HLEN+(int64_t)key->off;
        if (offset<0 || (uint64_t)offset>size || size-(size_t)offset<4) return 0;
        uint32_t word; memcpy(&word,frame+offset,4);
        if ((word^key->val)&key->mask) return 0;
    }
    return 1;
}
static int egress(const struct md_network_tc *tc,const void *data,size_t size,void **copy) {
    *copy=NULL;
    if (!tc) return 0;
    if (!tc->direct_qlen) return 1;
    if (size<ETH_HLEN || be16((const unsigned char *)data+12)!=ETH_P_IP) return 0;
    unsigned flags=0;
    for (unsigned i=0;i<tc->count;++i) if (matches(&tc->filters[i],data,size)) {
        flags=tc->filters[i].action.update_flags;
        break;
    }
    if (!flags) return 0;
    const unsigned char *ip=(const unsigned char *)data+ETH_HLEN;
    size_t available=size-ETH_HLEN;
    if (available<20 || ip[0]>>4!=4) return 1;
    unsigned ihl=(ip[0]&15)*4,total=be16(ip+2);
    if (ihl<20 || ihl>available || total<ihl || total>available) return 1;
    int udp=(flags&TCA_CSUM_UPDATE_FLAG_UDP) && ip[9]==IPPROTO_UDP && !(be16(ip+6)&0x1fff);
    unsigned length=total-ihl;
    if (udp && length<8) return 1;
    /* IPv4 UDP checksum zero means deliberately disabled, not partial. */
    udp=udp && be16(ip+ihl+6);
    if (!(flags&TCA_CSUM_UPDATE_FLAG_IPV4HDR) && !udp) return 0;
    unsigned char *frame=malloc(size);
    if (!frame) return -ENOMEM;
    memcpy(frame,data,size); unsigned char *v4=frame+ETH_HLEN;
    if (flags&TCA_CSUM_UPDATE_FLAG_IPV4HDR) {
        put16(v4+10,0); put16(v4+10,folded(sum(v4,ihl,0)));
    }
    if (udp) {
        unsigned char *u=v4+ihl; put16(u+6,0);
        if (be16(u+4)==length) {
            unsigned check=folded(sum(u,length,sum(v4+12,8,IPPROTO_UDP+length)));
            put16(u+6,check ? check : 0xffff);
        }
    }
    *copy=frame; return 0;
}
int md_network_tc_egress(struct md_network_tc *tc,const void *data,size_t size,void **copy) {
    int result=egress(tc,data,size,copy);
    if (tc && !result) ++tc->htb.direct_pkts;
    return result;
}
