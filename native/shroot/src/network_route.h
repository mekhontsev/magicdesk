#ifndef MD_NETWORK_ROUTE_H
#define MD_NETWORK_ROUTE_H
#include "network_namespace.h"
#include "network_message.h"
#include <linux/netlink.h>
struct md_network_fabric;
struct ifreq;
struct md_network_route_ioctl;
struct md_network_bridge_ioctl;
enum md_network_target_kind { MD_NETWORK_TARGET_NONE, MD_NETWORK_TARGET_FD, MD_NETWORK_TARGET_PID };
struct md_network_target_reference { enum md_network_target_kind kind; int value; };
/* Resolves guest descriptors or guest-visible PIDs, never host PID/path input. */
struct md_network_target {
    void *context;
    int (*resolve)(void *,struct md_network_target_reference,struct md_network_namespace **);
};
int md_network_route_query(unsigned);
/* Caller holds the fabric lock. Address removal and notification are atomic. */
int md_network_route_disable_ipv6(struct md_network_namespace *,uint64_t,unsigned);
/* Called by the shared network event loop under the fabric lock. */
int64_t md_network_route_expire(struct md_network_fabric *,int64_t);
int md_network_route_privacy(struct md_network_namespace *,uint64_t,unsigned);
/* Legacy ioctl admission uses the same transaction, policy and notifications. */
int md_network_route_set_address(struct md_network_namespace *,const struct md_identity *,const char *,const unsigned char [6]);
int md_network_route_set_flags(struct md_network_namespace *,const struct md_identity *,const char *,unsigned);
int md_network_route_set_mtu(struct md_network_namespace *,const struct md_identity *,const char *,unsigned);
int md_network_route_attach_bridge(struct md_network_namespace *,const struct md_identity *,const char *,unsigned);
int md_network_route_bridge(struct md_network_namespace *,const struct md_identity *,struct md_network_bridge_ioctl *);
int md_network_route_address_ioctl(struct md_network_namespace *,const struct md_identity *,unsigned,struct ifreq *);
int md_network_route_legacy(struct md_network_namespace *,const struct md_identity *,unsigned,const struct md_network_route_ioctl *);
/* Device-owner lifecycle, under the fabric lock. Creation has already committed;
 * final close cannot depend on allocating a topology rollback journal. */
void md_network_route_link_created(struct md_network_namespace *,unsigned);
void md_network_route_link_removed(struct md_network_namespace *,unsigned);
enum { MD_NETWORK_ROUTE_CAPPED_ACK=1, MD_NETWORK_ROUTE_STRICT=2 };
long md_network_route(struct md_network_namespace *,const struct md_identity *,
    const struct nlmsghdr *,size_t,uint32_t,unsigned options,const struct md_network_target *,void *,size_t,
    const struct md_network_publication *);
#endif
