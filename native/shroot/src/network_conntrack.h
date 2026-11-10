#ifndef MD_NETWORK_CONNTRACK_H
#define MD_NETWORK_CONNTRACK_H
#include "network_ip.h"
#include "network_message.h"

struct md_network_namespace;
struct md_identity;
struct md_connection_tuple {
    struct md_ip_address source, destination;
    uint16_t source_port, destination_port;
    unsigned protocol;
};
struct md_connection {
    uint64_t id;
    struct md_connection_tuple original, reply;
    uint32_t status, mark, timeout;
};
/* Managed relay state only. Enumeration and retirement hold the fabric lock;
 * native connected IO does not acquire another observer or tracking table. */
int md_network_forward_connections(struct md_network_namespace *,int (*)(void *,const struct md_connection *),void *);
/* Sorts the caller-owned ID batch before retirement. */
void md_network_forward_forget(struct md_network_namespace *,uint64_t *,size_t);
void md_network_forward_tracking_changed(struct md_network_namespace *);
long md_network_conntrack(struct md_network_namespace *,const struct md_identity *,const void *,size_t,
    uint32_t,int,void *,size_t,const struct md_network_publication *);
#endif
