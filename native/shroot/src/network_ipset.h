#ifndef MD_NETWORK_IPSET_H
#define MD_NETWORK_IPSET_H
#include "network_nft.h"
#include "network_filter.h"
long md_network_ipset(struct md_network_namespace *,const struct md_identity *,const void *,size_t,
    uint32_t,int,void *,size_t,const struct md_network_publication *);
/* Caller holds the namespace's fabric lock. */
long md_ipset_sockopt(const struct md_network_filter *,void *,size_t);
struct md_filter_set *md_ipset_find(struct md_network_filter *,const char *,unsigned);
int md_ipset_matches(const struct md_filter_set *,struct md_ip_address);
#endif
