#include "network_launch.h"
#include "network_internal.h"
#include "network_filter.h"
#include "network_filter_update.h"
#include "network_forward.h"
#include <errno.h>
#include <linux/netfilter.h>
#include <linux/netfilter/nf_tables.h>
#include <linux/if_addr.h>
#include <linux/rtnetlink.h>
#include <net/if.h>
#include <netinet/in.h>
#include <string.h>

static int configure(struct md_network_namespace *n,unsigned index,uint32_t ip,unsigned prefix) {
    struct md_network_interface *link=md_network_interface_find(n,index,NULL);
    link->info.flags|=IFF_UP; md_network_interface_up(link);
    if (ip) return md_network_interface_address(link,(struct md_network_ip_assignment){.address=md_ip_v4(ip),.prefix=prefix,
        .scope=index==1 ? RT_SCOPE_HOST : RT_SCOPE_UNIVERSE,.flags=IFA_F_PERMANENT},0,0);
    return 0;
}
static int topology(struct md_network_launch *launch,const struct md_network_family_config families[2]) {
    struct md_network_namespace *gateway=launch->gateway,*guest=launch->space;
    unsigned bridge=0,local=0,peer=0;
    int error=md_network_interface_add(gateway,MD_LINK_BRIDGE,"shroot0",&bridge);
    if (!error) error=md_network_interface_add(gateway,MD_LINK_VETH,"veth0",&local);
    if (!error) error=md_network_interface_add(guest,MD_LINK_VETH,"eth0",&peer);
    if (error) return error;
    configure(gateway,bridge,0,0);
    configure(gateway,local,0,0);
    configure(guest,peer,0,0);
    struct md_network_interface *left=md_network_interface_find(gateway,local,NULL);
    struct md_network_interface *right=md_network_interface_find(guest,peer,NULL);
    left->master=bridge; left->peer=peer; right->peer=local;
    struct md_network_interface *master=md_network_interface_find(gateway,bridge,NULL);
    for (unsigned i=0;i<2;++i) {
        const struct md_network_family_config *c=&families[i];
        error=md_network_interface_address(master,(struct md_network_ip_assignment){.address=c->gateway,
            .prefix=c->prefix,.scope=RT_SCOPE_UNIVERSE,.flags=IFA_F_PERMANENT},0,0);
        if (!error) error=md_network_interface_address(right,(struct md_network_ip_assignment){.address=c->address,
            .prefix=c->prefix,.scope=RT_SCOPE_UNIVERSE,.flags=IFA_F_PERMANENT},0,0);
        if (error) return error;
        guest->routes[guest->route_count++]=(struct md_network_route_entry){.destination=md_ip_any(c->address.family),
            .gateway=c->gateway,.interface=peer,.metric=i ? 1024 : 0,.protocol=RTPROT_BOOT};
    }
    /* Published loopback listeners enter through the private bridge. This is
     * scoped to this launch, never a host sysctl change. */
    guest->all.route_localnet=1; gateway->all.route_localnet=1;
    return 0;
}
static void compare(struct md_filter_rule *rule,unsigned size,const void *value) {
    struct md_filter_expression *e=&rule->expressions[rule->count++];
    *e=(struct md_filter_expression){.code=MD_FILTER_COMPARE,.size=size,.operation=NFT_CMP_EQ};
    memcpy(e->value,value,size);
}
static int publications(struct md_network_namespace *gateway,const struct md_network_profile *profile,
        const struct md_network_family_config families[2]) {
    if (!profile->count) return 0;
    struct md_network_filter *filter=md_network_filter_copy(NULL);
    if (!filter) return -ENOMEM;
    int error=md_network_filter_add(filter,NFPROTO_INET,"shroot",NULL,0);
    if (!error) error=md_filter_reserve((void **)&filter->chains,&filter->chain_capacity,4,sizeof(*filter->chains),256);
    if (!error) error=md_filter_reserve((void **)&filter->rules,&filter->rule_capacity,profile->count+4,sizeof(*filter->rules),1024);
    if (!error) {
        struct md_filter_chain chain={.table=filter->tables[0].handle,.handle=filter->next_handle++,
            .name="publish",.hook=-1,.policy=NF_ACCEPT};
        filter->chains[filter->chain_count++]=chain;
        for (unsigned i=0;i<profile->count;++i) {
            const struct md_network_public_port *port=&profile->ports[i];
            struct md_filter_rule *r=&filter->rules[filter->rule_count++];
            *r=(struct md_filter_rule){.chain=chain.handle,.handle=filter->next_handle++};
            int ipv6=port->address.family==AF_INET6;
            r->expressions[r->count++]=(struct md_filter_expression){.code=MD_FILTER_META,.key=NFT_META_NFPROTO,.size=1};
            unsigned char family=ipv6 ? NFPROTO_IPV6 : NFPROTO_IPV4;
            compare(r,1,&family);
            r->expressions[r->count++]=(struct md_filter_expression){.code=MD_FILTER_META,.key=NFT_META_L4PROTO,.size=1};
            unsigned char protocol=(unsigned char)port->protocol;
            compare(r,1,&protocol);
            unsigned bytes=md_ip_size(port->address);
            if (!md_ip_unspecified(port->address)) {
                r->expressions[r->count++]=(struct md_filter_expression){.code=MD_FILTER_PAYLOAD,
                    .key=NFT_PAYLOAD_NETWORK_HEADER,.aux=ipv6 ? 24 : 16,.size=bytes};
                compare(r,bytes,port->address.bytes);
            }
            r->expressions[r->count++]=(struct md_filter_expression){.code=MD_FILTER_PAYLOAD,
                .key=NFT_PAYLOAD_TRANSPORT_HEADER,.aux=2,.size=2};
            compare(r,2,&port->host);
            struct md_filter_expression *address=&r->expressions[r->count++];
            *address=(struct md_filter_expression){.code=MD_FILTER_IMMEDIATE,.dst=4,.size=bytes};
            struct md_ip_address destination=families[ipv6].address;
            memcpy(address->value,destination.bytes,bytes);
            struct md_filter_expression *translated=&r->expressions[r->count++];
            *translated=(struct md_filter_expression){.code=MD_FILTER_IMMEDIATE,.dst=20,.size=2};
            memcpy(translated->value,&port->guest,2);
            r->expressions[r->count++]=(struct md_filter_expression){.code=MD_FILTER_NAT,
                .operation=NFT_NAT_DNAT,.key=1,.aux=1,.src=20,.dst=4,.family=port->address.family};
        }
        for (unsigned i=0;i<2;++i) {
            struct md_filter_chain *base=&filter->chains[filter->chain_count++];
            *base=(struct md_filter_chain){.table=chain.table,.handle=filter->next_handle++,
                .hook=i ? NF_INET_LOCAL_OUT : NF_INET_PRE_ROUTING,.priority=-100,.nat=1,.policy=NF_ACCEPT};
            strcpy(base->name,i ? "output" : "prerouting");
            struct md_filter_rule *jump=&filter->rules[filter->rule_count++];
            *jump=(struct md_filter_rule){.chain=base->handle,.handle=filter->next_handle++,.count=1};
            jump->expressions[0]=(struct md_filter_expression){.code=MD_FILTER_VERDICT,
                .operation=NFT_JUMP,.target=chain.handle};
        }
        /* Loopback sources are not reachable through the guest's uplink.
         * Translate both families once; conntrack retains the native peer. */
        struct md_filter_chain *post=&filter->chains[filter->chain_count++];
        *post=(struct md_filter_chain){.table=chain.table,.handle=filter->next_handle++,
            .hook=NF_INET_POST_ROUTING,.priority=100,.nat=1,.policy=NF_ACCEPT};
        strcpy(post->name,"postrouting");
        for (unsigned ipv6=0;ipv6<2;++ipv6) {
            struct md_filter_rule *snat=&filter->rules[filter->rule_count++];
            *snat=(struct md_filter_rule){.chain=post->handle,.handle=filter->next_handle++};
            snat->expressions[snat->count++]=(struct md_filter_expression){.code=MD_FILTER_META,.key=NFT_META_NFPROTO,.size=1};
            unsigned char family=ipv6 ? NFPROTO_IPV6 : NFPROTO_IPV4;
            compare(snat,1,&family);
            unsigned bytes=ipv6 ? 16 : 1; /* ::1/128 or 127.0.0.0/8. */
            snat->expressions[snat->count++]=(struct md_filter_expression){.code=MD_FILTER_PAYLOAD,
                .key=NFT_PAYLOAD_NETWORK_HEADER,.aux=ipv6 ? 8 : 12,.size=bytes};
            struct md_ip_address loopback=md_ip_loopback(ipv6 ? AF_INET6 : AF_INET);
            compare(snat,bytes,loopback.bytes);
            snat->expressions[snat->count++]=(struct md_filter_expression){.code=MD_FILTER_MASQUERADE};
        }
    }
    struct md_network_forward_update *update=NULL;
    if (!error) error=md_network_filter_prepare(gateway,filter,&update);
    if (!error) filter=md_network_filter_commit(gateway,filter,update);
    else md_network_forward_abort(update);
    md_network_filter_free(filter); return error;
}
int md_network_launch_open(const struct md_network_profile *profile,struct md_user_namespace *owner,
        struct md_network_launch *launch) {
    if (!launch || launch->space || launch->gateway) return -EINVAL;
    int error=md_network_profile_validate(profile);
    if (error || profile->policy==MD_NETWORK_HOST) return error;
    struct md_network_family_config families[2];
    if (profile->policy==MD_NETWORK_BRIDGE) {
        for (unsigned i=0;i<2;++i) {
            error=md_network_profile_family(profile,i ? AF_INET6 : AF_INET,&families[i]);
            if (error) return error;
        }
        launch->gateway=md_network_namespace_new(owner,MD_NETWORK_ROUTED);
        if (!launch->gateway) return -errno;
        launch->space=md_network_namespace_copy(launch->gateway,owner);
    } else launch->space=md_network_namespace_new(owner,
        profile->policy==MD_NETWORK_BACKEND_ROUTED ? MD_NETWORK_ROUTED : MD_NETWORK_PRIVATE);
    if (!launch->space) { error=-errno; md_network_launch_close(launch); return error; }
    struct md_network_fabric *fabric=launch->space->fabric;
    pthread_mutex_lock(&fabric->lock);
    fabric->endpoints_enabled=1;
    error=configure(launch->space,1,htonl(INADDR_LOOPBACK),8);
    if (!error && launch->gateway) {
        error=topology(launch,families);
        if (!error) error=publications(launch->gateway,profile,families);
    }
    pthread_mutex_unlock(&fabric->lock);
    if (error) md_network_launch_close(launch);
    return error;
}
void md_network_launch_close(struct md_network_launch *launch) {
    if (!launch) return;
    md_network_namespace_release(launch->space);
    md_network_namespace_release(launch->gateway);
    *launch=(struct md_network_launch){0};
}
