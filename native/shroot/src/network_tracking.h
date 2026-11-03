#ifndef MD_NETWORK_TRACKING_H
#define MD_NETWORK_TRACKING_H
#include <stdint.h>
struct md_network_fabric;
struct md_network_namespace;
struct md_network_flow_path;
struct md_network_tracking_entry { uint64_t space,id; uint32_t mark; };
/* TCP relay metadata is independent of its native transport and NAT binding.
 * One entry per traversed namespace; payload activity shares one timestamp. */
struct md_network_tracking {
    struct md_network_tracking_entry entries[16];
    unsigned count, missing, half_closed;
    uint64_t revision;
    int64_t activity;
    uint32_t minimum_timeout[2];
};
void md_network_tracking_init(struct md_network_tracking *,struct md_network_fabric *,const struct md_network_flow_path *,uint64_t *,int64_t);
struct md_network_tracking_entry *md_network_tracking_find(struct md_network_tracking *,uint64_t);
void md_network_tracking_touch(struct md_network_tracking *,struct md_network_fabric *,uint64_t *,int64_t);
void md_network_tracking_forget(struct md_network_tracking *,struct md_network_fabric *,struct md_network_tracking_entry *);
void md_network_tracking_limits(struct md_network_tracking *,struct md_network_fabric *);
int64_t md_network_tracking_next(const struct md_network_tracking *);
int64_t md_network_tracking_deadline(const struct md_network_tracking *,const struct md_network_namespace *);
int64_t md_network_tracking_expire(struct md_network_tracking *,struct md_network_fabric *,int64_t);
#endif
