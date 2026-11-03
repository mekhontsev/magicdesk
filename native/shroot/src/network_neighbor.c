#include "network_neighbor.h"
#include "network_internal.h"
#include "network_message.h"
#include "network_stack.h"
#include "netlink_attributes.h"
#include <linux/neighbour.h>
#include <linux/rtnetlink.h>
#include <linux/sockios.h>
#include <stdlib.h>
#include <string.h>

struct entry { unsigned index,family; uint64_t lifetime; struct md_ip_address ip; unsigned char mac[6]; uint16_t state; };
struct md_network_neighbors { unsigned references,count; struct entry entries[]; };
struct md_network_neighbors *md_network_neighbors_retain(struct md_network_neighbors *s) { if(s) ++s->references; return s; }
void md_network_neighbors_release(struct md_network_neighbors *s) { if(s && !--s->references) free(s); }
static int compare(const void *left,const void *right) {
    const struct entry *a=left,*b=right;
    if(a->index!=b->index) return a->index<b->index ? -1 : 1;
    if(a->family!=b->family) return a->family<b->family ? -1 : 1;
    return a->family==AF_BRIDGE ? memcmp(a->mac,b->mac,6) : memcmp(a->ip.bytes,b->ip.bytes,md_ip_size(a->ip));
}
static const struct entry *find(const struct md_network_neighbors *s,const struct entry *key) {
    return s ? bsearch(key,s->entries,s->count,sizeof(*key),compare) : NULL;
}
static int equal(const struct entry *a,const struct entry *b) {
    return b && a->lifetime==b->lifetime && a->state==b->state
        && md_ip_equal(a->ip,b->ip) && !memcmp(a->mac,b->mac,6);
}
static int live(struct md_network_namespace *n,const struct entry *e) {
    const struct md_network_interface *l=md_network_interface_find(n,e->index,NULL);
    return l && l->policy_id==e->lifetime;
}
int md_network_neighbors_prune(struct md_network_namespace *n) {
    struct md_network_neighbors *before=n->neighbors;
    if(!before) return 0;
    unsigned count=0;
    for(unsigned i=0;i<before->count;++i) count+=live(n,&before->entries[i]);
    if(count==before->count) return 0;
    struct md_network_neighbors *after=before;
    if(before->references>1) {
        after=malloc(sizeof(*after)+count*sizeof(struct entry));
        if(!after) return -ENOMEM;
        after->references=1;
    }
    count=0;
    for(unsigned i=0;i<before->count;++i)
        if(live(n,&before->entries[i])) after->entries[count++]=before->entries[i];
    after->count=count;
    if(after!=before) { n->neighbors=after; md_network_neighbors_release(before); }
    return 0;
}
int md_network_neighbor_mac(struct md_network_namespace *n,const struct md_network_interface *l,
        struct md_ip_address ip,unsigned char mac[6]) {
    const struct entry key={.index=l->info.index,.family=ip.family,.ip=ip};
    const struct entry *e=find(n->neighbors,&key);
    if(!e || e->lifetime!=l->policy_id) return 0;
    memcpy(mac,e->mac,6); return 1;
}
int md_network_neighbor_remote(struct md_network_namespace *n,const struct md_network_interface *l,
        const unsigned char mac[6],struct md_ip_address *remote) {
    struct entry key={.index=l->info.index,.family=AF_BRIDGE}; memcpy(key.mac,mac,6);
    const struct entry *e=find(n->neighbors,&key);
    if(!e || e->lifetime!=l->policy_id) return 0;
    *remote=e->ip; return 1;
}
static int reply(struct md_network_message *out,const struct nlmsghdr *h,uint32_t port,int dump,const struct entry *e) {
    struct ndmsg m={.ndm_family=e->family,.ndm_ifindex=(int)e->index,.ndm_state=e->state,
        .ndm_flags=e->family==AF_BRIDGE ? NTF_SELF : 0,.ndm_type=RTN_UNICAST};
    int error=md_network_message_begin(out,h,port,RTM_NEWNEIGH,dump,&m,sizeof(m));
    if(!error) error=md_network_message_attribute(out,NDA_DST,e->ip.bytes,md_ip_size(e->ip));
    if(!error) error=md_network_message_attribute(out,NDA_LLADDR,e->mac,6);
    return error;
}
static int update(struct md_network_namespace *n,const struct entry *key,int deleting) {
    unsigned count=0;
    for(unsigned i=0;n->neighbors && i<n->neighbors->count;++i)
        if(live(n,&n->neighbors->entries[i]) && compare(key,&n->neighbors->entries[i])) ++count;
    if(!deleting) ++count;
    if(count>1024) return -ENOSPC;
    struct md_network_neighbors *next=malloc(sizeof(*next)+count*sizeof(*key));
    if(!next) return -ENOMEM;
    *next=(struct md_network_neighbors){.references=1};
    for(unsigned i=0;n->neighbors && i<n->neighbors->count;++i) {
        const struct entry *e=&n->neighbors->entries[i];
        if(live(n,e) && compare(key,e)) next->entries[next->count++]=*e;
    }
    if(!deleting) next->entries[next->count++]=*key;
    qsort(next->entries,next->count,sizeof(*key),compare);
    md_network_neighbors_release(n->neighbors); n->neighbors=next; return 0;
}
int md_network_neighbors_control(struct md_network_namespace *n,const struct nlmsghdr *h,uint32_t port,int dump,
        struct md_network_message *out) {
    if(h->nlmsg_len<NLMSG_LENGTH(sizeof(struct ndmsg))) return -EINVAL;
    const struct ndmsg *m=NLMSG_DATA(h);
    if(m->ndm_ifindex<0 || m->ndm_pad1 || m->ndm_pad2) return -EINVAL;
    if(m->ndm_family!=AF_UNSPEC && m->ndm_family!=AF_INET && m->ndm_family!=AF_INET6 && m->ndm_family!=AF_BRIDGE) return -EAFNOSUPPORT;
    struct entry key={.index=(unsigned)m->ndm_ifindex,.family=m->ndm_family};
    int has_ip=0,has_mac=0;
    struct md_netlink_cursor cursor={(const char *)m+NLMSG_ALIGN(sizeof(*m)),h->nlmsg_len-NLMSG_LENGTH(sizeof(*m))};
    struct md_netlink_attribute a; int error;
    while((error=md_netlink_attribute_next(&cursor,&a))>0) {
        if(a.type==NDA_DST) {
            if(has_ip || (a.size!=4 && a.size!=16) || (key.family!=AF_BRIDGE && a.size!=md_ip_size(md_ip_any(key.family)))) return -EINVAL;
            key.ip=md_ip_load(a.size==4 ? AF_INET : AF_INET6,a.data); has_ip=1;
        } else if(a.type==NDA_LLADDR) {
            if(has_mac || a.size!=6) return -EINVAL;
            memcpy(key.mac,a.data,6); has_mac=1;
        } else if(a.type==NDA_IFINDEX && dump) {
            if(a.size!=4 || key.index) return -EINVAL;
            memcpy(&key.index,a.data,4);
        } else return -EOPNOTSUPP;
    }
    if(error) return error;
    if(h->nlmsg_type==RTM_GETNEIGH && dump) {
        if(has_ip || has_mac || m->ndm_state || m->ndm_type || (m->ndm_flags&~NTF_SELF)) return -EOPNOTSUPP;
        for(unsigned i=0;n->neighbors && i<n->neighbors->count;++i) {
            const struct entry *e=&n->neighbors->entries[i];
            if((key.family && key.family!=e->family) || (key.index && key.index!=e->index) || !live(n,e)) continue;
            error=reply(out,h,port,1,e); if(error) return error;
        }
        return 0;
    }
    struct md_network_interface *link=md_network_interface_find(n,key.index,NULL);
    if(!key.index || !link) return -ENODEV;
    key.lifetime=link->policy_id;
    if(key.family==AF_UNSPEC || (key.family==AF_BRIDGE ? !has_mac : !has_ip)) return -EINVAL;
    if(key.family==AF_BRIDGE && link->kind!=MD_LINK_VXLAN) return -EOPNOTSUPP;
    const struct entry *found=find(n->neighbors,&key);
    if(found && !live(n,found)) found=NULL;
    if(h->nlmsg_type==RTM_GETNEIGH) return found ? reply(out,h,port,0,found) : -ENOENT;
    if(m->ndm_flags!=(key.family==AF_BRIDGE ? NTF_SELF : 0) || (m->ndm_type && m->ndm_type!=RTN_UNICAST)) return -EOPNOTSUPP;
    int deleting=h->nlmsg_type==RTM_DELNEIGH;
    if(deleting && (!found || (has_ip && !md_ip_equal(found->ip,key.ip)) || (has_mac && memcmp(found->mac,key.mac,6)))) return -ENOENT;
    if(!deleting) {
        unsigned state=m->ndm_state;
        if(key.family==AF_BRIDGE && (state&NUD_NOARP)) state&=~NUD_NOARP;
        if((state!=NUD_PERMANENT && !(key.family==AF_BRIDGE && (m->ndm_state&NUD_NOARP)
                && (state==NUD_REACHABLE || !state))) || !has_mac || !has_ip) return -EOPNOTSUPP;
        key.state=m->ndm_state;
        if(md_ip_unspecified(key.ip) || md_ip_multicast(key.ip) || (key.mac[0]&1)) return -EINVAL;
        if(key.family!=AF_BRIDGE && !memcmp(key.mac,"\0\0\0\0\0\0",6)) return -EINVAL;
        if(found && (h->nlmsg_flags&NLM_F_EXCL)) return -EEXIST;
        if(!found && !(h->nlmsg_flags&NLM_F_CREATE)) return -ENOENT;
        if(found && !(h->nlmsg_flags&NLM_F_REPLACE)) return -EEXIST;
    }
    return update(n,&key,deleting);
}
int md_network_neighbor_arp(struct md_network_namespace *n,const struct md_network_interface *l,
        unsigned command,struct md_ip_address ip,unsigned char mac[6],int permanent) {
    struct entry key={.index=l->info.index,.family=AF_INET,.ip=ip,.lifetime=l->policy_id,.state=NUD_PERMANENT};
    const struct entry *found=find(n->neighbors,&key);
    if(found && !live(n,found)) found=NULL;
    if(command==SIOCGARP) {
        if(found) memcpy(mac,found->mac,6);
        return found!=NULL;
    }
    int setting=command==SIOCSARP && permanent;
    if(!setting && !found) return 0;
    if(setting && (md_ip_unspecified(ip) || md_ip_multicast(ip) || (mac[0]&1) || !memcmp(mac,"\0\0\0\0\0\0",6))) return -EINVAL;
    memcpy(key.mac,mac,6);
    struct md_network_neighbors *before=md_network_neighbors_retain(n->neighbors);
    int error=update(n,&key,!setting);
    if(!error) { md_network_neighbors_commit(n,before); md_network_neighbors_notify(n,before); }
    md_network_neighbors_release(before);
    return error ? error : setting || command==SIOCDARP;
}
static unsigned changes(struct md_network_namespace *n,const struct md_network_neighbors *before,int publish) {
    if(before==n->neighbors) return 0;
    unsigned published=0;
    for(unsigned direction=0;direction<2;++direction) {
        const struct md_network_neighbors *from=direction ? n->neighbors : before,*to=direction ? before : n->neighbors;
        for(unsigned i=0;from && i<from->count;++i) {
            const struct entry *e=&from->entries[i],*other=find(to,e);
            if(equal(e,other)) continue;
            if(!publish) {
                if(e->family!=AF_BRIDGE && live(n,e)) md_network_stack_neighbor_forget(n,e->index,e->ip);
                continue;
            }
            union { struct nlmsghdr align; unsigned char bytes[128]; } bytes;
            struct md_network_message out={.data=bytes.bytes,.capacity=sizeof(bytes)};
            const struct nlmsghdr h={0};
            if(!reply(&out,&h,0,0,e)) {
                bytes.align.nlmsg_type=direction ? RTM_NEWNEIGH : RTM_DELNEIGH;
                published+=md_network_broadcast(n,RTNLGRP_NEIGH,bytes.bytes,out.size);
            }
        }
    }
    return published;
}
unsigned md_network_neighbors_notify(struct md_network_namespace *n,const struct md_network_neighbors *before) {
    return changes(n,before,1);
}
void md_network_neighbors_commit(struct md_network_namespace *n,const struct md_network_neighbors *before) {
    changes(n,before,0);
}
