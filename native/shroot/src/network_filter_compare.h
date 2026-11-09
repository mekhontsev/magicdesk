#ifndef MD_NETWORK_FILTER_COMPARE_H
#define MD_NETWORK_FILTER_COMPARE_H
#include <linux/netfilter/nf_tables.h>

/* order is the bytewise comparison of the original nft register values. */
static inline int md_filter_compare(unsigned operation,int order) {
    switch(operation) {
    case NFT_CMP_EQ: return order==0;
    case NFT_CMP_NEQ: return order!=0;
    case NFT_CMP_LT: return order<0;
    case NFT_CMP_LTE: return order<=0;
    case NFT_CMP_GT: return order>0;
    case NFT_CMP_GTE: return order>=0;
    default: return 0;
    }
}
#endif
