#ifndef MD_NETWORK_CONTROL_QUEUE_H
#define MD_NETWORK_CONTROL_QUEUE_H
#include <stddef.h>
struct md_network_fabric;
struct md_network_event_source;
struct md_control_reply;
/* Socket-owned replies. All calls and readiness callbacks hold the fabric
 * lock; cancellation precedes closing the borrowed peer descriptor. */
struct md_network_control_queue {
    struct md_control_reply *head, *tail;
    struct md_network_event_source *event;
    size_t bytes;
    int peer, error;
};
int md_network_control_publish(struct md_network_control_queue *,struct md_network_fabric *,int,const void *,size_t);
void md_network_control_clear(struct md_network_control_queue *);
#endif
