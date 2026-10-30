#include "network_link.h"
#include "network_internal.h"
#include <errno.h>
#include <net/if.h>
#include <stdlib.h>

struct hop { struct md_network_namespace *space; struct md_network_interface *link; int outgoing; };
static void enqueue(struct hop *queue,size_t *count,struct md_network_namespace *n,struct md_network_interface *link,int outgoing) {
    if (!link || !(link->info.flags&IFF_UP)) return;
    for (size_t i=0;i<*count;++i) if (queue[i].space==n && queue[i].link==link && queue[i].outgoing==outgoing) return;
    queue[(*count)++]=(struct hop){n,link,outgoing};
}
static int walk(struct md_network_namespace *space,struct md_network_interface *source,int link_local,
        md_network_link_visitor visitor,void *context,int outgoing) {
    size_t capacity=0;
    for (struct md_network_namespace *n=space->fabric->spaces;n;n=n->next) {
        if (n->count>(SIZE_MAX/sizeof(struct hop)-capacity)/2) return -EOVERFLOW;
        capacity+=2*n->count;
    }
    struct hop *queue=calloc(capacity,sizeof(*queue));
    if (!queue) return -ENOMEM;
    size_t count=0;
    enqueue(queue,&count,space,source,outgoing);
    for (size_t at=0;at<count;++at) {
        struct hop hop=queue[at];
        struct md_network_interface *link=hop.link;
        visitor(context,hop.space,link,hop.outgoing);
        if (hop.outgoing && link->kind==MD_LINK_LOOPBACK) enqueue(queue,&count,hop.space,link,0);
        else if (hop.outgoing && link->kind==MD_LINK_VETH) {
            struct md_network_namespace *peer=md_network_interface_owner(hop.space->fabric,link->peer);
            if (peer) enqueue(queue,&count,peer,md_network_interface_find(peer,link->peer,NULL),0);
        } else if (link->kind==MD_LINK_BRIDGE || (!hop.outgoing && link->master)) {
            unsigned bridge=link->kind==MD_LINK_BRIDGE ? link->info.index : link->master;
            if (link->master) enqueue(queue,&count,hop.space,md_network_interface_find(hop.space,bridge,NULL),0);
            /* Ingress ports, not a second walk from the bridge master, own
             * flooding. This retains isolation/hairpin policy through cycles. */
            if ((link->kind!=MD_LINK_BRIDGE || hop.outgoing) && (!link_local || hop.outgoing))
                for (unsigned i=0;i<hop.space->count;++i) {
                    struct md_network_interface *port=&hop.space->links[i];
                    if (port->master==bridge && md_network_bridge_forward(link->kind==MD_LINK_BRIDGE ? NULL : link,port))
                        enqueue(queue,&count,hop.space,port,1);
                }
        }
    }
    free(queue); return 0;
}
int md_network_link_walk(struct md_network_namespace *space,struct md_network_interface *source,int link_local,
        md_network_link_visitor visitor,void *context) { return walk(space,source,link_local,visitor,context,1); }
int md_network_link_ingress(struct md_network_namespace *space,struct md_network_interface *source,int link_local,
        md_network_link_visitor visitor,void *context) { return walk(space,source,link_local,visitor,context,0); }
