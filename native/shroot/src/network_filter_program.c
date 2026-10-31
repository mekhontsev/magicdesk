#include "network_filter.h"
#include <errno.h>
#include <linux/netfilter.h>
#include <linux/netfilter/nf_tables.h>
#include <linux/rtnetlink.h>
#include <linux/netfilter/xt_rpfilter.h>
#include <linux/if_packet.h>
#include <netinet/in.h>
#include <string.h>

static int chain_index(const struct md_network_filter *f,uint64_t handle) {
    for (size_t i=0;i<f->chain_count;++i) if (f->chains[i].handle==handle) return (int)i;
    return -1;
}
static int tcp_predicate(const struct md_network_filter *f,const struct md_filter_rule *r,unsigned end) {
    unsigned family=0;
    int chain=chain_index(f,r->chain);
    for (size_t i=0;i<f->count;++i)
        if (f->tables[i].handle==f->chains[chain].table) family=f->tables[i].family;
    for (unsigned i=0;i<end;++i) {
        const struct md_filter_expression *e=&r->expressions[i];
        if (e->code==MD_FILTER_TRANSPORT && e->transport.protocol==IPPROTO_TCP) return 1;
        if (i && e->code==MD_FILTER_COMPARE && e->operation==NFT_CMP_EQ
                && e->size==1 && e->value[0]==IPPROTO_TCP) {
            const struct md_filter_expression *load=&r->expressions[i-1];
            if (load->dst!=e->src) continue;
            if (load->code==MD_FILTER_META && load->key==NFT_META_L4PROTO) return 1;
            if (load->code==MD_FILTER_PAYLOAD && load->key==NFT_PAYLOAD_NETWORK_HEADER && load->size==1
                    && ((family==NFPROTO_IPV4 && load->aux==9) || (family==NFPROTO_IPV6 && load->aux==6))) return 1;
        }
    }
    return 0;
}
static int validate_chain(const struct md_network_filter *f,size_t chain,unsigned char *state,
        unsigned char *height,unsigned depth) {
    if (state[chain]==1 || depth>16) return -ELOOP;
    if (state[chain]==2) return depth+height[chain]>16 ? -ELOOP : 0;
    state[chain]=1;
    const struct md_filter_chain *c=&f->chains[chain];
    for (size_t i=0;i<f->rule_count;++i) if (f->rules[i].chain==c->handle) {
        const struct md_filter_rule *r=&f->rules[i];
        for (unsigned j=0;j<r->count;++j) {
            const struct md_filter_expression *e=&r->expressions[j];
            for (size_t k=0;k<md_filter_verdict_count(f,e);++k) {
                struct md_filter_verdict v=md_filter_verdict_at(f,e,k);
                if ((int)v.operation!=NFT_JUMP && (int)v.operation!=NFT_GOTO) continue;
                int target=chain_index(f,v.target);
                if (target<0) return -ENOENT;
                if (f->chains[target].table!=c->table || f->chains[target].hook>=0) return -EOPNOTSUPP;
                int error=validate_chain(f,(size_t)target,state,height,depth+1);
                if (error) return error;
                if (height[target]+1>height[chain]) height[chain]=height[target]+1;
            }
            if (md_filter_set_expression(e)) {
                int found=0;
                for (size_t k=0;k<f->set_count;++k)
                    if (f->sets[k].handle==e->target && f->sets[k].table==c->table) found=1;
                if (!found) return -ENOENT;
            }
        }
    }
    state[chain]=2; return 0;
}
int md_filter_validate(const struct md_network_filter *f) {
    for (size_t i=0;i<f->set_count;++i) {
        const struct md_filter_set *s=&f->sets[i];
        for (size_t j=1;j<s->count;++j) {
            int order=memcmp(s->elements[j-1].key,s->elements[j].key,s->key_size);
            if (order>0 || (!order && s->elements[j-1].end<=s->elements[j].end)) return -EINVAL;
            if ((s->flags&NFT_SET_INTERVAL) && s->elements[j-1].end==s->elements[j].end) return -EEXIST;
        }
    }
    unsigned char state[256]={0};
    unsigned char height[256]={0};
    unsigned contexts[256]={0};
    for (size_t i=0;i<f->chain_count;++i) {
        int error=validate_chain(f,i,state,height,0);
        if (error) return error;
        if (f->chains[i].hook>=0) contexts[i]=1U<<(f->chains[i].hook
            +(f->chains[i].nat ? 0 : NF_INET_NUMHOOKS));
    }
    /* Low bits describe NAT hooks, high bits filter hooks. Regular chains
     * inherit every calling context, including jumps through verdict maps. */
    for (unsigned changed=1;changed;) {
        changed=0;
        for (size_t i=0;i<f->rule_count;++i) {
            const struct md_filter_rule *r=&f->rules[i];
            int source=chain_index(f,r->chain);
            if (source<0) return -ENOENT;
            for (unsigned j=0;j<r->count;++j) {
                const struct md_filter_expression *e=&r->expressions[j];
                for (size_t k=0;k<md_filter_verdict_count(f,e);++k) {
                    struct md_filter_verdict v=md_filter_verdict_at(f,e,k);
                    if ((int)v.operation!=NFT_JUMP && (int)v.operation!=NFT_GOTO) continue;
                    int target=chain_index(f,v.target);
                    unsigned added=contexts[source]&~contexts[target];
                    contexts[target]|=added; changed|=added;
                }
            }
        }
    }
    for (size_t i=0;i<f->rule_count;++i) {
        const struct md_filter_rule *r=&f->rules[i];
        unsigned context=contexts[chain_index(f,r->chain)];
        for (unsigned j=0;j<r->count;++j) {
            const struct md_filter_expression *e=&r->expressions[j];
            unsigned allowed;
            if (e->code==MD_FILTER_MASQUERADE) allowed=1U<<NF_INET_POST_ROUTING;
            else if (e->code==MD_FILTER_NAT) allowed=e->operation==NFT_NAT_DNAT
                ? (1U<<NF_INET_PRE_ROUTING)|(1U<<NF_INET_LOCAL_OUT)
                : (1U<<NF_INET_POST_ROUTING)|(1U<<NF_INET_LOCAL_IN);
            else if (e->code==MD_FILTER_FIB) {
                if (e->key!=NFT_FIB_RESULT_ADDRTYPE || (e->operation&NFTA_FIB_F_IIF))
                    allowed=(1U<<NF_INET_PRE_ROUTING)|(1U<<NF_INET_LOCAL_IN)|(1U<<NF_INET_FORWARD);
                else if (e->operation&NFTA_FIB_F_OIF)
                    allowed=(1U<<NF_INET_LOCAL_OUT)|(1U<<NF_INET_POST_ROUTING)|(1U<<NF_INET_FORWARD);
                else continue;
                allowed|=allowed<<NF_INET_NUMHOOKS;
            }
            else if (e->code==MD_FILTER_REJECT) {
                if (!md_network_reject_valid(e->key,e->aux)
                        || (e->key==MD_REJECT_TCP && !tcp_predicate(f,r,j))) return -EOPNOTSUPP;
                allowed=((1U<<NF_INET_LOCAL_IN)|(1U<<NF_INET_FORWARD)|(1U<<NF_INET_LOCAL_OUT))<<NF_INET_NUMHOOKS;
            }
            else continue;
            if (context&~allowed) return -EOPNOTSUPP;
        }
    }
    return 0;
}
static int account_counter(struct md_filter_packet *p,struct md_filter_counter *counter) {
    /* Existing native streams cannot move into a relay. Counters observe
     * mediated traffic; unlike marks and verdicts they do not change admission. */
    if (p->unmediated) return 0;
    struct md_filter_accounting *a=p->accounting;
    if (!a) return 0;
    int error=md_filter_reserve((void **)&a->cells,&a->capacity,a->count+1,sizeof(*a->cells),1024);
    if (!error) a->cells[a->count++]=counter;
    return error;
}
static int reverse_path(const struct md_filter_packet *p,unsigned flags) {
    if (!p->input) return 0;
    if (p->input==1) return 1;
    if (md_ip_unspecified(p->source) && (p->source.family==AF_INET6
            || p->packet_type==PACKET_BROADCAST || p->packet_type==PACKET_MULTICAST)) return 1;
    if (!p->route) return 0;
    unsigned query=NFTA_FIB_F_SADDR;
    if (!(flags&XT_RPFILTER_LOOSE) || md_ip_linklocal(p->source)) query|=NFTA_FIB_F_IIF;
    if (flags&XT_RPFILTER_VALID_MARK) query|=NFTA_FIB_F_MARK;
    struct md_filter_route_result route=p->route(p->route_context,p,query);
    if (route.type!=RTN_UNICAST && !(route.type==RTN_LOCAL && (flags&XT_RPFILTER_ACCEPT_LOCAL))) return 0;
    return !!route.interface;
}
static int source_nat(const struct md_filter_expression *e,const unsigned char *registers,struct md_filter_packet *p) {
    if (p->protocol!=IPPROTO_TCP && p->protocol!=IPPROTO_UDP) return e->aux ? -EOPNOTSUPP : 0;
    unsigned port=ntohs(p->source_port);
    uint16_t low=port<512 ? 1 : port<1024 ? 600 : 1024,high=port<512 ? 511 : port<1024 ? 1023 : 65535;
    if (e->aux) {
        memcpy(&low,e->constant ? (const void *)&e->nat_port : registers+e->src,2);
        high=low;
        if (e->port_range) {
            if (e->constant) high=(uint16_t)e->port_max;
            else memcpy(&high,registers+e->port_max,2);
        }
        low=ntohs(low); high=ntohs(high);
        if (low>high) return -EINVAL;
    }
    if (p->source_nat) return p->source_nat(p->route_context,p,low,high);
    if (port<low || port>high) p->source_port=htons(low);
    return 0;
}
static int evaluate_chain(struct md_network_filter *f,const struct md_filter_chain *c,
        struct md_filter_packet *p,unsigned depth) {
    if (depth>16) return -ELOOP;
    for (size_t i=0;i<f->rule_count;++i) {
        struct md_filter_rule *r=&f->rules[i];
        if (r->chain!=c->handle) continue;
        unsigned char registers[64]={0};
        int match=1;
        for (unsigned j=0;j<r->count && match;++j) {
            struct md_filter_expression *e=&r->expressions[j];
            unsigned char *src=registers+e->src,*dst=registers+e->dst;
            uint32_t value=0;
            switch (e->code) {
            case MD_FILTER_REJECT:
                if (e->key==MD_REJECT_TCP && p->protocol!=IPPROTO_TCP) return -EOPNOTSUPP;
                p->rejection=md_network_reject_resolve(e->key,e->aux,p->destination.family);
                return md_network_reject_error(p->rejection);
            case MD_FILTER_ICMP_MATCH: {
                int matches=(e->key==IPPROTO_ICMP && e->value[0]==255)
                    || (p->transport[0]==e->value[0] && p->transport[1]>=e->value[1]
                        && p->transport[1]<=e->value[2]);
                match=p->protocol==e->key && p->transport_size>=2 && (matches!=!!e->value[3]);
                break;
            }
            case MD_FILTER_TRANSPORT: {
                const struct md_filter_transport *t=&e->transport;
                match=p->protocol==t->protocol
                    && md_filter_port_matches(ntohs(p->source_port),t->source,t->inversion&MD_FILTER_INVERT_SOURCE)
                    && md_filter_port_matches(ntohs(p->destination_port),t->destination,t->inversion&MD_FILTER_INVERT_DESTINATION);
                break;
            }
            case MD_FILTER_COUNTER: {
                int error=account_counter(p,&e->counter);
                if (error) return error;
                break;
            }
            case MD_FILTER_IMMEDIATE:
                memset(dst,0,(e->size+3)&~3U); memcpy(dst,e->value,e->size); break;
            case MD_FILTER_COMPARE: {
                int same=!memcmp(src,e->value,e->size);
                match=e->operation==NFT_CMP_EQ ? same : !same; break;
            }
            case MD_FILTER_RANGE: {
                int inside=memcmp(src,e->value,e->size)>=0 && memcmp(src,e->mask,e->size)<=0;
                match=e->operation==NFT_RANGE_EQ ? inside : !inside; break;
            }
            case MD_FILTER_BITWISE: {
                unsigned char result[16];
                for (unsigned k=0;k<e->size;++k) result[k]=(src[k]&e->mask[k])^e->value[k];
                memcpy(dst,result,e->size);
                memset(dst+e->size,0,((e->size+3)&~3U)-e->size);
                break;
            }
            case MD_FILTER_PAYLOAD: {
                unsigned char header[40]={0};
                if (e->key==NFT_PAYLOAD_NETWORK_HEADER) {
                    if (p->destination.family==AF_INET6) {
                        header[0]=0x60; header[6]=(unsigned char)p->protocol;
                        memcpy(header+8,p->source.bytes,16); memcpy(header+24,p->destination.bytes,16);
                    } else {
                        header[0]=0x45; header[9]=(unsigned char)p->protocol;
                        memcpy(header+12,p->source.bytes,4); memcpy(header+16,p->destination.bytes,4);
                    }
                } else if (p->protocol==IPPROTO_TCP || p->protocol==IPPROTO_UDP) {
                    memcpy(header,&p->source_port,2); memcpy(header+2,&p->destination_port,2);
                } else {
                    if (e->aux+e->size>p->transport_size) { match=0; break; }
                    memcpy(header,p->transport,p->transport_size);
                }
                if (e->aux+e->size>(e->key==NFT_PAYLOAD_NETWORK_HEADER
                        ? (p->destination.family==AF_INET6 ? 40U : 20U) : 4U)) { match=0; break; }
                memset(dst,0,(e->size+3)&~3U); memcpy(dst,header+e->aux,e->size); break;
            }
            case MD_FILTER_META:
                switch (e->key) {
                case NFT_META_NFPROTO: value=p->destination.family==AF_INET6 ? NFPROTO_IPV6 : NFPROTO_IPV4; break;
                case NFT_META_L4PROTO: value=p->protocol; break;
                case NFT_META_PKTTYPE: value=p->packet_type; break;
                case NFT_META_MARK: value=p->mark; break;
                case NFT_META_IIF: value=p->input; break;
                case NFT_META_OIF: value=p->output; break;
                case NFT_META_IIFNAME: memcpy(dst,p->input_name,16); break;
                case NFT_META_OIFNAME: memcpy(dst,p->output_name,16); break;
                }
                if (e->size<=4) memcpy(dst,&value,4);
                break;
            case MD_FILTER_META_SET: case MD_FILTER_CT_SET: {
                unsigned *destination=e->code==MD_FILTER_META_SET ? &p->mark : &p->ctmark;
                if (e->constant) {
                    uint32_t mask;
                    memcpy(&value,e->value,4); memcpy(&mask,e->mask,4);
                    if (e->constant==2) value=(e->code==MD_FILTER_META_SET ? p->ctmark : p->mark)&e->aux;
                    *destination=(*destination&~mask)^value;
                } else memcpy(destination,src,4);
                break;
            }
            case MD_FILTER_CT:
                value=e->key==NFT_CT_STATE ? p->state : e->key==NFT_CT_MARK ? p->ctmark : p->status;
                memcpy(dst,&value,4); break;
            case MD_FILTER_STATE_MATCH: match=!!(p->state&e->key)!=!!e->operation; break;
            case MD_FILTER_MARK_MATCH: case MD_FILTER_CONNMARK_MATCH:
                match=(((e->code==MD_FILTER_CONNMARK_MATCH ? p->ctmark : p->mark)&e->aux)==e->key)!=!!e->operation; break;
            case MD_FILTER_PACKET_TYPE: match=(p->packet_type==e->key)!=!!e->operation; break;
            case MD_FILTER_REVERSE_PATH:
                match=reverse_path(p,e->key)!=!!(e->key&XT_RPFILTER_INVERT); break;
            case MD_FILTER_ADDRESS_TYPE:
                match=(!e->key || (!!(e->key&(1U<<p->source_fib_type))!=!!(e->operation&MD_FILTER_INVERT_SOURCE)))
                    && (!e->aux || (!!(e->aux&(1U<<p->fib_type))!=!!(e->operation&MD_FILTER_INVERT_DESTINATION)));
                break;
            case MD_FILTER_FIB: {
                struct md_filter_route_result route={.type=e->operation&NFTA_FIB_F_SADDR ? p->source_fib_type : p->fib_type};
                if (p->route) route=p->route(p->route_context,p,e->operation);
                memset(dst,0,e->size);
                if (e->key==NFT_FIB_RESULT_ADDRTYPE) value=route.type;
                else if (e->operation&NFTA_FIB_F_PRESENT) value=!!route.interface;
                else if (e->key==NFT_FIB_RESULT_OIFNAME) { memcpy(dst,route.name,16); break; }
                else value=route.interface;
                memcpy(dst,&value,4); break;
            }
            case MD_FILTER_LOOKUP: {
                struct md_filter_set *s=md_filter_set_find(f,c->table,NULL,e->target,0);
                if (!s) return -ENOENT;
                int found=md_filter_set_lookup(s,src)!=NULL;
                match=e->operation ? !found : found; break;
            }
            case MD_FILTER_DATA_MAP: {
                struct md_filter_set *s=md_filter_set_find(f,c->table,NULL,e->target,0);
                if (!s) return -ENOENT;
                const struct md_filter_element *element=md_filter_set_lookup(s,src);
                if (!element) { match=0; break; }
                memcpy(dst,element->data,s->data_size);
                memset(dst+s->data_size,0,((s->data_size+3)&~3U)-s->data_size);
                break;
            }
            case MD_FILTER_NAT:
                if (e->family && e->family!=p->destination.family) { match=0; break; }
                if (e->operation==NFT_NAT_DNAT) {
                    if (e->key) memcpy(p->destination.bytes,e->constant ? e->value : registers+e->dst,md_ip_size(p->destination));
                    if (e->aux) memcpy(&p->destination_port,e->constant ? (const void *)&e->nat_port : registers+e->src,2);
                    p->dnat=1; p->status|=1U<<5;
                } else {
                    if (e->key) memcpy(p->source.bytes,e->constant ? e->value : registers+e->dst,md_ip_size(p->source));
                    int error=source_nat(e,registers,p); if (error) return error;
                    p->snat=1; p->status|=1U<<4;
                }
                return NF_ACCEPT;
            case MD_FILTER_MASQUERADE:
                p->source=p->masquerade_address;
                { int error=source_nat(e,registers,p); if (error) return error; }
                p->snat=p->masquerade=1; p->status|=1U<<4;
                return NF_ACCEPT;
            case MD_FILTER_VERDICT_MAP:
            case MD_FILTER_VERDICT: {
                struct md_filter_verdict v={e->operation,e->target};
                if (e->code==MD_FILTER_VERDICT_MAP) {
                    struct md_filter_set *s=md_filter_set_find(f,c->table,NULL,e->target,0);
                    if (!s) return -ENOENT;
                    const struct md_filter_element *element=md_filter_set_lookup(s,src);
                    if (!element) { match=0; break; }
                    v=element->verdict;
                }
                if ((int)v.operation==NFT_JUMP || (int)v.operation==NFT_GOTO) {
                    struct md_filter_chain *target=md_filter_chain_find(f,c->table,NULL,v.target,0);
                    if (!target) return -ENOENT;
                    int verdict=evaluate_chain(f,target,p,depth+1);
                    if (verdict!=NFT_RETURN || (int)v.operation==NFT_GOTO) return verdict;
                } else if ((int)v.operation==NFT_BREAK) match=0;
                else if ((int)v.operation!=NFT_CONTINUE) return (int)v.operation;
                break;
            }
            }
        }
    }
    return NFT_RETURN;
}
int md_filter_evaluate(struct md_network_filter *f,unsigned hook,struct md_filter_packet *p) {
    return md_filter_evaluate_transition(f,hook,p,NULL,NULL);
}
int md_filter_chain_applies(const struct md_network_filter *f,const struct md_filter_chain *c,unsigned family) {
    unsigned protocol=family==AF_INET6 ? NFPROTO_IPV6 : NFPROTO_IPV4;
    for (size_t i=0;i<f->count;++i) if (f->tables[i].handle==c->table)
        return f->tables[i].family==NFPROTO_INET || f->tables[i].family==protocol;
    return 0;
}
int md_filter_evaluate_transition(struct md_network_filter *f,unsigned hook,struct md_filter_packet *p,
        const struct md_filter_rewrite *rewrite,int *nat_priority) {
    p->rejection=(struct md_network_rejection){0};
    /* Order is stable for equal priorities. NF_ACCEPT ends a chain, not the
     * hook: a later base chain may still deny the flow. */
    int previous_priority=INT32_MIN;
    size_t previous_index=0;
    int started=0,retained=rewrite!=NULL;
    for (;;) {
        size_t count=f ? f->chain_count : 0,selected=count;
        for (size_t i=0;i<count;++i) {
            struct md_filter_chain *c=&f->chains[i];
            if (!md_filter_chain_applies(f,c,p->destination.family) || c->hook!=(int)hook || (started && (c->priority<previous_priority
                    || (c->priority==previous_priority && i<=previous_index)))) continue;
            if (selected==count || c->priority<f->chains[selected].priority) selected=i;
        }
        /* Retained conntrack translation is a hook-stage operation, not an
         * already-rewritten input tuple. Early raw rules see the wire address;
         * later filters see the translated destination/source. */
        if (rewrite && (selected==count || f->chains[selected].priority>=rewrite->priority)) {
            p->source=rewrite->source; p->destination=rewrite->destination;
            p->source_port=rewrite->source_port; p->destination_port=rewrite->destination_port;
            p->fib_type=rewrite->fib_type; p->source_fib_type=rewrite->source_fib_type;
            rewrite=NULL;
        }
        if (selected==count) return 0;
        struct md_filter_chain *c=&f->chains[selected];
        unsigned initial_mark=p->mark;
        struct md_ip_address initial_source=p->source,initial_destination=p->destination;
        int mapped=(hook==NF_INET_PRE_ROUTING || hook==NF_INET_LOCAL_OUT) ? p->dnat : p->snat;
        int verdict=c->nat && (retained || p->state!=8 || mapped) ? NF_ACCEPT : evaluate_chain(f,c,p,0);
        if (nat_priority && c->nat && !mapped && (p->dnat || p->snat)) *nat_priority=c->priority;
        if (verdict==NFT_RETURN) {
            if (c->counters) {
                int error=account_counter(p,&c->counter);
                if (error) return error;
            }
            verdict=(int)c->policy;
        }
        if (verdict!=NF_ACCEPT) return verdict==NF_DROP ? -EACCES : verdict<0 ? verdict : -EOPNOTSUPP;
        if (c->reroute && (p->mark!=initial_mark || !md_ip_equal(p->source,initial_source)
                || !md_ip_equal(p->destination,initial_destination))) {
            if (!p->route) return -EOPNOTSUPP;
            struct md_filter_route_result route=p->route(p->route_context,p,NFTA_FIB_F_DADDR|NFTA_FIB_F_MARK);
            if (!route.interface) return -ENETUNREACH;
            p->output=route.interface; memcpy(p->output_name,route.name,sizeof(p->output_name));
        }
        previous_priority=c->priority; previous_index=selected; started=1;
    }
}
