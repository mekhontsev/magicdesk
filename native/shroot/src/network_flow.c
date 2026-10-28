#include "network_flow.h"
#include <errno.h>
#include <linux/netfilter.h>
#include <linux/netfilter/nf_tables.h>
#include <linux/netfilter_ipv4.h>
#include <linux/rtnetlink.h>
#include <linux/if_packet.h>
#include <net/if.h>
#include <netinet/in.h>
#include <string.h>
#include <stdlib.h>

struct md_network_namespace *md_network_space_find(struct md_network_fabric *f,uint64_t inode) {
    for (struct md_network_namespace *n=f->spaces;n;n=n->next) if (n->inode==inode) return n;
    return NULL;
}
struct md_network_interface *md_network_local_interface(struct md_network_namespace *n,struct md_ip_address ip,int up) {
    for (unsigned i=0;i<n->count;++i) {
        struct md_network_interface *l=&n->links[i];
        if ((!up || (l->info.flags&IFF_UP)) && md_network_interface_has(l,ip)) return l;
    }
    return NULL;
}
static void interface(struct md_filter_packet *p,const struct md_network_interface *link,int input) {
    char *name=input ? p->input_name : p->output_name;
    memset(name,0,16);
    if (input) p->input=link ? link->info.index : 0;
    else { p->output=link ? link->info.index : 0; p->masquerade_address=md_network_interface_source(link,p->destination); }
    if (link) memcpy(name,link->info.name,strnlen(link->info.name,15));
}
static int requires_mediation(const struct md_network_filter *f,const struct md_filter_chain *c,unsigned depth) {
    if (c->hook>=0 && (c->policy==NF_DROP || c->counters)) return 1;
    if (depth>16) return 1;
    for (size_t i=0;i<f->rule_count;++i) if (f->rules[i].chain==c->handle)
        for (unsigned j=0;j<f->rules[i].count;++j) {
            const struct md_filter_expression *e=&f->rules[i].expressions[j];
            if (e->code==MD_FILTER_COUNTER || e->code==MD_FILTER_REJECT) return 1;
            for (size_t at=0;at<md_filter_verdict_count(f,e);++at) {
                struct md_filter_verdict v=md_filter_verdict_at(f,e,at);
                if (v.operation==NF_DROP) return 1;
                if ((int)v.operation==NFT_JUMP || (int)v.operation==NFT_GOTO)
                    for (size_t k=0;k<f->chain_count;++k)
                        if (f->chains[k].handle==v.target && requires_mediation(f,&f->chains[k],depth+1)) return 1;
            }
        }
    return 0;
}
static unsigned reverse_hook(unsigned hook) {
    switch (hook) {
    case NF_INET_PRE_ROUTING: return NF_INET_POST_ROUTING;
    case NF_INET_POST_ROUTING: return NF_INET_PRE_ROUTING;
    case NF_INET_LOCAL_IN: return NF_INET_LOCAL_OUT;
    case NF_INET_LOCAL_OUT: return NF_INET_LOCAL_IN;
    default: return hook;
    }
}
static struct md_filter_rewrite tuple(const struct md_filter_packet *p) {
    return (struct md_filter_rewrite){.source=p->source,.destination=p->destination,
        .source_port=p->source_port,.destination_port=p->destination_port};
}
struct route_context { struct md_network_namespace *space; const struct md_network_flow_path *path; };
static int native_local(const struct route_context *context,struct md_ip_address address) {
    return context->path->native_space==context->space->inode
        && md_ip_equal(address,context->path->native_address);
}
static unsigned address_type(const struct route_context *context,struct md_ip_address address) {
    return native_local(context,address) ? RTN_LOCAL : md_network_address_type(context->space,address);
}
static struct md_filter_route_result filter_route(void *opaque,const struct md_filter_packet *p,unsigned flags) {
    const struct route_context *context=opaque;
    struct md_network_namespace *n=context->space;
    struct md_ip_address address=flags&NFTA_FIB_F_SADDR ? p->source : p->destination;
    unsigned index=flags&NFTA_FIB_F_OIF ? p->output : 0;
    if (address.family==AF_INET6 && (flags&NFTA_FIB_F_IIF)) index=p->input;
    struct md_network_interface *link=md_network_route_lookup(n,address,index,NULL);
    if (!index && native_local(context,address)) link=md_network_interface_find(n,1,NULL);
    struct md_filter_route_result result={.type=RTN_UNREACHABLE};
    if (link) {
        /* A link-local address on another link is not local to this route. */
        result.type=md_ip_linklocal(address)
            ? (md_network_interface_has(link,address) ? RTN_LOCAL : RTN_UNICAST)
            : address_type(context,address);
        if (!(flags&NFTA_FIB_F_IIF) || link->info.index==p->input) {
            result.interface=link->info.index;
            memcpy(result.name,link->info.name,sizeof(result.name));
        }
    }
    return result;
}
static int hook(struct md_network_namespace *n,unsigned which,struct md_filter_packet *p,struct md_network_flow_path *path) {
    struct route_context context={n,path};
    p->source_fib_type=address_type(&context,p->source);
    p->fib_type=address_type(&context,p->destination);
    if (path->count==16) return -ELOOP;
    struct md_network_flow_step *step=&path->steps[path->count++];
    *step=(struct md_network_flow_step){.space=n->inode,.hook=which,.packet=*p};
    step->after=tuple(p);
    struct md_network_filter *f=n->filter;
    if (!f || !f->chain_count) return 0;
    /* Native connected IO bypasses both directions. A policy on replies also
     * requires mediation, even when every outbound hook accepts the request. */
    for (size_t i=0;i<f->chain_count;++i)
        if (md_filter_chain_applies(f,&f->chains[i],p->destination.family)
                && (f->chains[i].hook==(int)which || f->chains[i].hook==(int)reverse_hook(which))
                && requires_mediation(f,&f->chains[i],0)) path->mediated=1;
    int priority=0;
    p->route_context=&context; p->route=filter_route;
    int error=md_filter_evaluate_transition(f,which,p,NULL,&priority);
    p->route_context=NULL; p->route=NULL;
    step->after=tuple(p); step->after.priority=priority;
    if (p->snat || p->dnat) path->mediated=1;
    return error;
}
static struct md_network_interface *bridge_peer(struct md_network_namespace *n,const struct md_network_interface *bridge,
        struct md_ip_address destination,struct md_network_namespace **space) {
    for (struct md_network_namespace *s=n->fabric->spaces;s;s=s->next) {
        struct md_network_interface *in=md_network_local_interface(s,destination,1);
        if (!in || in->kind!=MD_LINK_VETH) continue;
        struct md_network_interface *peer=md_network_interface_find(n,in->peer,NULL);
        if (peer && peer->master==bridge->info.index && (peer->info.flags&IFF_UP)) { *space=s; return in; }
    }
    return NULL;
}
int md_network_flow_packet_policy(struct md_network_namespace *n,const struct md_network_interface *link,
        struct md_filter_packet *p,int input,size_t bytes) {
    if (!n->filter || !n->filter->chain_count) return 0;
    struct md_filter_accounting accounting={0};
    struct md_network_flow_path path={0};
    p->accounting=&accounting;
    p->fib_type=md_network_address_type(n,p->destination);
    p->packet_type=p->fib_type==RTN_MULTICAST ? PACKET_MULTICAST
        : p->fib_type==RTN_BROADCAST ? PACKET_BROADCAST : PACKET_HOST;
    interface(p,input ? link : NULL,1); interface(p,input ? NULL : link,0);
    int error=hook(n,input ? NF_INET_PRE_ROUTING : NF_INET_LOCAL_OUT,p,&path);
    if (!error) error=hook(n,input ? NF_INET_LOCAL_IN : NF_INET_POST_ROUTING,p,&path);
    /* Raw packets are already materialized. A tuple-only NAT change must not
     * masquerade as rewriting their headers and checksum. */
    if (!error && (p->snat || p->dnat)) error=-EOPNOTSUPP;
    md_filter_account(&accounting,bytes); free(accounting.cells); p->accounting=NULL;
    return error;
}
int md_network_flow_group_output(struct md_network_namespace *n,struct md_network_interface *out,
        struct md_filter_packet *p,struct md_network_flow_path *path) {
    *path=(struct md_network_flow_path){.generation=n->fabric->policy_generation,.mediated=1};
    p->packet_type=md_ip_multicast(p->destination) ? PACKET_MULTICAST : PACKET_BROADCAST;
    p->fib_type=md_ip_multicast(p->destination) ? RTN_MULTICAST : RTN_BROADCAST;
    interface(p,NULL,1); interface(p,out,0);
    path->original=*p;
    int error=hook(n,NF_INET_LOCAL_OUT,p,path);
    if (!error) error=hook(n,NF_INET_POST_ROUTING,p,path);
    if (!error && (p->snat || p->dnat)) error=-EOPNOTSUPP;
    path->translated=*p; return error;
}
int md_network_flow_group_input(struct md_network_namespace *n,struct md_network_interface *in,
        struct md_network_flow_path *path) {
    struct md_filter_packet p=path->translated;
    int error=md_network_ingress(n,in,p.source,p.destination);
    interface(&p,in,1); interface(&p,NULL,0);
    if (!error) error=hook(n,NF_INET_PRE_ROUTING,&p,path);
    if (!error) error=hook(n,NF_INET_LOCAL_IN,&p,path);
    if (!error && (p.snat || p.dnat)) error=-EOPNOTSUPP;
    path->translated=p; path->destination=n->inode; return error;
}
int md_network_flow_route(struct md_network_namespace *from,struct md_filter_packet *p,int external,
        unsigned output,struct md_network_flow_path *path) {
    *path=(struct md_network_flow_path){.generation=from->fabric->policy_generation};
    /* Only the native listener knows its accepted destination is local.
     * A raw ingress packet supplies an interface, not this authority. */
    if (external && !output) { path->native_space=from->inode; path->native_address=p->destination; }
    struct route_context context={from,path};
    struct md_network_namespace *n=from;
    if (md_ip_multicast(p->destination)) p->packet_type=PACKET_MULTICAST;
    else if (md_network_address_type(n,p->destination)==RTN_BROADCAST) p->packet_type=PACKET_BROADCAST;
    struct md_network_route_entry origin={0};
    struct md_network_interface *out=md_network_route_lookup(n,p->destination,output,&origin);
    if (!external) path->transport=md_network_route_transport(out,&origin);
    if (!external && md_ip_unspecified(p->source)) p->source=md_network_route_source(n,out,&origin,p->destination);
    interface(p,out,0);
    p->fib_type=address_type(&context,p->destination);
    path->original=*p;
    int arrived=external;
    unsigned ingress_port=0;
    if (external) interface(p,output ? md_network_interface_find(n,output,NULL)
        : md_ip_is_loopback(p->source) ? md_network_interface_find(n,1,NULL) : md_network_route_find(n,p->source,NULL),1);
    int error=hook(n,external ? NF_INET_PRE_ROUTING : NF_INET_LOCAL_OUT,p,path);
    while (!error) {
        struct md_network_interface *local=md_network_local_interface(n,p->destination,1);
        if (local && md_ip_linklocal(p->destination) && n==from && output && local->info.index!=output) local=NULL;
        context.space=n;
        if (local || native_local(&context,p->destination)) {
            interface(p,NULL,0);
            if (!arrived) interface(p,local,1);
            error=hook(n,NF_INET_LOCAL_IN,p,path);
            path->destination=n->inode; break;
        }
        struct md_network_route_entry route;
        out=md_network_route_lookup(n,p->destination,n==from ? output : 0,&route);
        if (!out || !(out->info.flags&IFF_UP)) { error=-ENETUNREACH; break; }
        struct md_network_transport_policy transport=md_network_route_transport(out,&route);
        if (transport.mtu && (!path->transport.mtu || transport.mtu<path->transport.mtu)) path->transport.mtu=transport.mtu;
        interface(p,out,0);
        if (arrived) {
            if (md_ip_linklocal(p->source) || md_ip_linklocal(p->destination)) { error=-ENETUNREACH; break; }
            if (!md_network_forwarding(n,p->destination.family,p->input)) { error=-ENETUNREACH; break; }
            error=hook(n,NF_INET_FORWARD,p,path);
        }
        if (!error) error=hook(n,NF_INET_POST_ROUTING,p,path);
        if (error) break;
        if (out->kind==MD_LINK_UPLINK) {
            if (n->mode!=MD_NETWORK_ROUTED) error=-ENETUNREACH;
            else if (md_ip_multicast(p->destination)
                    || (p->destination.family==AF_INET && p->destination.v4==INADDR_BROADCAST)) error=-EOPNOTSUPP;
            break;
        }
        struct md_network_namespace *next=NULL;
        struct md_network_interface *input=NULL;
        if (out->kind==MD_LINK_VETH) {
            next=md_network_interface_owner(n->fabric,out->peer);
            input=next ? md_network_interface_find(next,out->peer,NULL) : NULL;
            if (input && input->master) input=md_network_interface_find(next,input->master,NULL);
        } else if (out->kind==MD_LINK_BRIDGE) {
            input=bridge_peer(n,out,md_ip_unspecified(route.gateway) ? p->destination : route.gateway,&next);
            if (input && ingress_port) {
                struct md_network_interface *in=md_network_interface_find(n,ingress_port,NULL);
                struct md_network_interface *port=md_network_interface_find(n,input->peer,NULL);
                if (!in || !port) { error=-ENETUNREACH; break; }
                if (in->master==port->master && !md_network_bridge_forward(in,port)) { error=-EACCES; break; }
                if (in->master==port->master && (in==port || in->isolated || port->isolated)) path->mediated=1;
                for (unsigned i=in->master==port->master ? path->count : 0;i>0;--i) {
                    struct md_network_flow_step *step=&path->steps[i-1];
                    if (step->space==n->inode && step->hook==NF_INET_FORWARD) {
                        step->bridge_ingress=ingress_port; step->bridge_egress=input->peer; break;
                    }
                }
            }
        }
        if (!next || !input || !(input->info.flags&IFF_UP)) { error=-ENETUNREACH; break; }
        error=md_network_ingress(next,input,p->source,p->destination);
        if (error) break;
        ingress_port=out->kind==MD_LINK_VETH ? out->peer : 0;
        n=next; arrived=1; external=0;
        interface(p,input,1); interface(p,md_network_route_find(n,p->destination,NULL),0);
        p->fib_type=md_network_address_type(n,p->destination);
        error=hook(n,NF_INET_PRE_ROUTING,p,path);
    }
    path->translated=*p; return error;
}
static int check_path(struct md_network_fabric *f,struct md_network_flow_path *path,int reverse,
        struct md_network_namespace *override,struct md_network_filter *filter,int initial,struct md_filter_accounting *accounting) {
    unsigned mark=0;
    for (unsigned k=0;k<path->count;++k) {
        unsigned i=reverse ? path->count-k-1 : k;
        struct md_network_flow_step *s=&path->steps[i];
        struct md_network_namespace *n=md_network_space_find(f,s->space);
        if (!n) { if (override) continue; return -ENETUNREACH; }
        if (s->bridge_ingress) {
            struct md_network_interface *in=md_network_interface_find(n,reverse ? s->bridge_egress : s->bridge_ingress,NULL);
            struct md_network_interface *out=md_network_interface_find(n,reverse ? s->bridge_ingress : s->bridge_egress,NULL);
            if (!in || !md_network_bridge_forward(in,out)) return -EACCES;
        }
        struct md_filter_packet p=s->packet;
        struct route_context context={n,path};
        p.route_context=&context; p.route=filter_route;
        struct md_filter_rewrite rewrite=s->after;
        /* NEW data may arrive after a ruleset change. Keep its admitted NAT
         * binding (including no translation), while evaluating current filters. */
        int retained=reverse || !initial || override || path->generation!=f->policy_generation;
        p.state=initial && !reverse ? s->packet.state : 2;
        p.accounting=accounting;
        p.ctmark=path->translated.ctmark; p.status=path->translated.status; p.mark=mark;
        struct md_network_interface *input=md_network_interface_find(n,p.input,NULL);
        struct md_network_interface *output=md_network_interface_find(n,p.output,NULL);
        if (!override && ((p.input && (!input || !(input->info.flags&IFF_UP)))
                || (p.output && (!output || !(output->info.flags&IFF_UP))))) return -ENETUNREACH;
        interface(&p,input,1); interface(&p,output,0);
        unsigned hook=s->hook;
        if (reverse) {
            p.source=s->after.destination; p.destination=s->after.source;
            p.source_port=s->after.destination_port; p.destination_port=s->after.source_port;
            rewrite=tuple(&s->packet);
            struct md_ip_address address=rewrite.source; rewrite.source=rewrite.destination; rewrite.destination=address;
            uint16_t port=rewrite.source_port; rewrite.source_port=rewrite.destination_port; rewrite.destination_port=port;
            interface(&p,md_network_interface_find(n,s->packet.output,NULL),1);
            interface(&p,md_network_interface_find(n,s->packet.input,NULL),0);
            hook=reverse_hook(hook);
            rewrite.priority=(hook==NF_INET_PRE_ROUTING || hook==NF_INET_LOCAL_OUT)
                ? NF_IP_PRI_NAT_DST : NF_IP_PRI_NAT_SRC;
        }
        if (!override && hook==NF_INET_FORWARD && !md_network_forwarding(n,p.destination.family,p.input)) return -ENETUNREACH;
        p.fib_type=address_type(&context,p.destination);
        p.source_fib_type=address_type(&context,p.source);
        rewrite.fib_type=address_type(&context,rewrite.destination);
        rewrite.source_fib_type=address_type(&context,rewrite.source);
        /* Existing connections retain even an untranslated NAT binding. The
         * evaluator skips NAT chains for them, including counters and jumps;
         * only live filter chains can revoke their native data path. */
        p.unmediated=override==n;
        int error=md_filter_evaluate_transition(n==override ? filter : n->filter,hook,&p,
            retained ? &rewrite : NULL,NULL);
        if (error) return error;
        path->translated.ctmark=p.ctmark;
        mark=p.mark;
    }
    return 0;
}
int md_network_flow_check(struct md_network_fabric *f,struct md_network_flow_path *path,int reverse) {
    return check_path(f,path,reverse,NULL,NULL,0,NULL);
}
int md_network_flow_accounting(struct md_network_fabric *f,struct md_network_flow_path *path,int reverse,int initial,
        struct md_filter_accounting *accounting) {
    accounting->count=0;
    return check_path(f,path,reverse,NULL,NULL,initial,accounting);
}
int md_network_flow_admits(struct md_network_namespace *n,struct md_network_filter *filter,const struct md_network_flow_path *path) {
    int affected=0;
    for (unsigned i=0;i<path->count;++i) affected|=path->steps[i].space==n->inode;
    if (!affected) return 0;
    struct md_network_flow_path copy=*path;
    /* Both first payload and established traffic can outlive publication.
     * Probe only the retained tuple, without counting or mutating its marks. */
    int error=check_path(n->fabric,&copy,0,n,filter,1,NULL);
    if (!error) error=check_path(n->fabric,&copy,0,n,filter,0,NULL);
    if (!error) error=check_path(n->fabric,&copy,1,n,filter,0,NULL);
    return error==-EACCES || error==-ECONNREFUSED ? -EBUSY : error;
}
