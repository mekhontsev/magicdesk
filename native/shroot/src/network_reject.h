#ifndef MD_NETWORK_REJECT_H
#define MD_NETWORK_REJECT_H
#include <stdint.h>

/* Policy describes a refusal independently of its socket or packet carrier.
 * ICMP and ICMPX resolve the packet family at evaluation, not rule parsing.
 * ICMP retains the wire code; ICMPX translates its family-neutral code. */
enum md_network_reject_type {
    MD_REJECT_NONE, MD_REJECT_TCP, MD_REJECT_ICMP4, MD_REJECT_ICMP6, MD_REJECT_ICMPX,
    MD_REJECT_ICMP
};
struct md_network_rejection { unsigned char type, code; };
int md_network_reject_valid(unsigned type,unsigned code);
struct md_network_rejection md_network_reject_resolve(unsigned type,unsigned code,unsigned family);
int md_network_reject_error(struct md_network_rejection);
#endif
