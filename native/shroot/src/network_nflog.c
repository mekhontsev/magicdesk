#include "network_nflog.h"
#include "network_internal.h"
#include "network_nft_attr.h"
#include "guest_identity.h"
#include <linux/netfilter/nfnetlink_log.h>
#include <linux/if_ether.h>

/* Log subscriptions belong to the control socket. No worker, timer, Android
 * netfilter socket or observer on ordinary application IO is needed. */
long md_network_nflog(struct md_network_namespace *n,const struct md_identity *identity,const void *data,size_t size,
        uint32_t port,int capped,void *reply,size_t capacity,const struct md_network_publication *publication) {
    const struct nlmsghdr *h=data;
    if (size<sizeof(*h) || h->nlmsg_len<NLMSG_LENGTH(sizeof(struct nfgenmsg)) || h->nlmsg_len>size
            || NLMSG_ALIGN(h->nlmsg_len)!=size) return -EINVAL;
    const struct nfgenmsg *nf=NLMSG_DATA(h);
    unsigned group=ntohs(nf->res_id);
    struct md_network_message out={.data=reply,.capacity=capacity};
    pthread_mutex_lock(&n->fabric->lock);
    int error=md_identity_capable_in(identity,n->owner,CAP_NET_ADMIN) ? 0 : -EPERM;
    if (!error && (!(h->nlmsg_flags&NLM_F_REQUEST) || nf->version!=NFNETLINK_V0)) error=-EINVAL;
    if (!error && NFNL_MSG_TYPE(h->nlmsg_type)!=NFULNL_MSG_CONFIG) error=-EOPNOTSUPP;
    struct attribute a[NFULA_CFG_MAX+1];
    if (!error) error=attributes(h,BIT(NFULA_CFG_CMD)|BIT(NFULA_CFG_MODE)|BIT(NFULA_CFG_FLAGS)
        |BIT(NFULA_CFG_NLBUFSIZ)|BIT(NFULA_CFG_TIMEOUT)|BIT(NFULA_CFG_QTHRESH),a,NFULA_CFG_MAX+1);
    unsigned command=NFULNL_CFG_CMD_NONE;
    int metadata=-1;
    if (!error && a[NFULA_CFG_CMD].data) {
        if (a[NFULA_CFG_CMD].size!=1 || a[NFULA_CFG_CMD].flags) error=-EINVAL;
        else command=*(const unsigned char *)a[NFULA_CFG_CMD].data;
    }
    if (!error && a[NFULA_CFG_MODE].data) {
        struct nfulnl_msg_config_mode mode;
        if (a[NFULA_CFG_MODE].size!=sizeof(mode) || a[NFULA_CFG_MODE].flags) error=-EINVAL;
        else {
            memcpy(&mode,a[NFULA_CFG_MODE].data,sizeof(mode));
            /* Managed relay admission exposes metadata, not invented payload. */
            if (mode.copy_mode!=NFULNL_COPY_META && mode.copy_mode!=NFULNL_COPY_NONE) error=-EOPNOTSUPP;
            else metadata=mode.copy_mode==NFULNL_COPY_META;
        }
    }
    if (!error && a[NFULA_CFG_FLAGS].data) {
        uint16_t flags;
        if (a[NFULA_CFG_FLAGS].size!=2 || (a[NFULA_CFG_FLAGS].flags&~NLA_F_NET_BYTEORDER)) error=-EINVAL;
        else { memcpy(&flags,a[NFULA_CFG_FLAGS].data,2); if (flags) error=-EOPNOTSUPP; }
    }
    for (unsigned k=NFULA_CFG_NLBUFSIZ;!error && k<=NFULA_CFG_QTHRESH;++k) {
        uint64_t value; error=number(&a[k],&value,0);
    }
    struct md_network_listener *self=NULL,*owner=NULL;
    unsigned slot=16,owned=16;
    if (!error) {
        for (struct md_network_listener *s=n->listeners;s;s=s->next) if (s->protocol==NETLINK_NETFILTER) {
            if (s->port==port) self=s;
            for (unsigned k=0;k<16;++k) if (s->logs[k]==group+1) { owner=s; owned=k; }
        }
        if (!self) error=-ENOENT;
        else for (unsigned k=0;k<16;++k) if (!self->logs[k]) { slot=k; break; }
    }
    if (!error) switch (command) {
    case NFULNL_CFG_CMD_BIND:
        if (owner) error=-EBUSY;
        else if (slot==16) error=-ENOSPC;
        break;
    case NFULNL_CFG_CMD_UNBIND:
        if (!owner) error=-ENOENT;
        else if (owner!=self) error=-EPERM;
        break;
    case NFULNL_CFG_CMD_NONE: if (owner!=self) error=-ENOENT; break;
    case NFULNL_CFG_CMD_PF_BIND: case NFULNL_CFG_CMD_PF_UNBIND:
        if (nf->nfgen_family!=AF_INET && nf->nfgen_family!=AF_INET6) error=-EAFNOSUPPORT;
        else if (metadata>=0) error=-EINVAL;
        break;
    default: error=-EOPNOTSUPP; break;
    }
    int encoded=md_network_message_done(&out,h,port,error,0,capped);
    long result=encoded ? encoded : publication && out.size ? publication->publish(publication->context,out.data,out.size) : (long)out.size;
    if (!error && result>=0) {
        if (command==NFULNL_CFG_CMD_BIND) { self->logs[slot]=group+1; owned=slot; }
        else if (command==NFULNL_CFG_CMD_UNBIND) { self->logs[owned]=0; self->log_metadata&=~(1U<<owned); }
        if (metadata>=0 && owned<16 && command!=NFULNL_CFG_CMD_UNBIND) {
            self->log_metadata&=~(1U<<owned);
            if (metadata) self->log_metadata|=1U<<owned;
        }
    }
    pthread_mutex_unlock(&n->fabric->lock); return result;
}
void md_network_nflog_emit(struct md_network_namespace *n,const struct md_filter_packet *p,const struct md_filter_log *log) {
    struct md_network_listener *owner=NULL;
    for (struct md_network_listener *s=n->listeners;s && !owner;s=s->next)
        if (s->protocol==NETLINK_NETFILTER) for (unsigned k=0;k<16;++k)
            if (s->logs[k]==log->group+1 && (s->log_metadata&(1U<<k))) { owner=s; break; }
    if (!owner) return;
    unsigned char bytes[256];
    struct md_network_message out={.data=bytes,.capacity=sizeof(bytes)};
    struct nlmsghdr h={0};
    struct nfgenmsg nf={.nfgen_family=p->destination.family,.version=NFNETLINK_V0,.res_id=htons(log->group)};
    struct nfulnl_msg_packet_hdr packet={.hw_protocol=htons(p->destination.family==AF_INET6 ? ETH_P_IPV6 : ETH_P_IP),.hook=p->hook};
    int error=md_network_message_begin(&out,&h,owner->port,(NFNL_SUBSYS_ULOG<<8)|NFULNL_MSG_PACKET,0,&nf,sizeof(nf));
    if (!error) error=md_network_message_attribute(&out,NFULA_PACKET_HDR,&packet,sizeof(packet));
    if (!error && *log->prefix) error=md_network_message_attribute(&out,NFULA_PREFIX,log->prefix,strlen(log->prefix)+1);
    const unsigned types[]={NFULA_MARK,NFULA_IFINDEX_INDEV,NFULA_IFINDEX_OUTDEV};
    const uint32_t values[]={htonl(p->mark),htonl(p->input),htonl(p->output)};
    for (unsigned i=0;!error && i<3;++i) error=md_network_message_attribute(&out,types[i],values+i,4);
    if (!error) md_network_listener_deliver(owner,out.data,out.size);
}
