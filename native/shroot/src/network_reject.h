#ifndef MD_NETWORK_REJECT_H
#define MD_NETWORK_REJECT_H
#include <stdint.h>

/* Policy describes a refusal independently of its socket or packet carrier.
 * ICMPX exists only in rules; a packet decision always has a concrete family. */
enum md_network_reject_type {
    MD_REJECT_NONE, MD_REJECT_TCP, MD_REJECT_ICMP4, MD_REJECT_ICMP6, MD_REJECT_ICMPX
};
struct md_network_rejection { unsigned char type, code; };
int md_network_reject_valid(unsigned type,unsigned code);
struct md_network_rejection md_network_reject_resolve(unsigned type,unsigned code,unsigned family);
int md_network_reject_error(struct md_network_rejection);
#endif
