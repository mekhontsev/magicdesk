#ifndef MD_NETWORK_NFLOG_H
#define MD_NETWORK_NFLOG_H
#include "network_nft.h"
#include "network_filter.h"
long md_network_nflog(struct md_network_namespace *,const struct md_identity *,const void *,size_t,
    uint32_t,int,void *,size_t,const struct md_network_publication *);
void md_network_nflog_emit(struct md_network_namespace *,const struct md_filter_packet *,const struct md_filter_log *);
#endif
