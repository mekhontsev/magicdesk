#include "network_link.h"
#include "network_internal.h"
#include <errno.h>
#include <net/if.h>
#include <stdlib.h>

struct hop { struct md_network_namespace *space; struct md_network_interface *link; int outgoing; const void *payload; };
static void enqueue(struct hop *queue,size_t *count,struct md_network_namespace *n,struct md_network_interface *link,int outgoing,const void *payload) {
    if (!link || !(link->info.flags&IFF_UP)) return;
    for (size_t i=0;i<*count;++i) if (queue[i].space==n && queue[i].link==link && queue[i].outgoing==outgoing) return;
    queue[(*count)++]=(struct hop){n,link,outgoing,payload};
}
static int walk(struct md_network_namespace *space,struct md_network_interface *source,int link_local,
        md_network_link_visitor visitor,void *context,const void *payload,int outgoing) {
    size_t capacity=0;
    for (struct md_network_namespace *n=space->fabric->spaces;n;n=n->next) {
        if (n->count>(SIZE_MAX/sizeof(struct hop)-capacity)/2) return -EOVERFLOW;
        capacity+=2*n->count;
    }
    struct hop *queue=calloc(capacity,sizeof(*queue));
    if (!queue) return -ENOMEM;
    size_t count=0;
    enqueue(queue,&count,space,source,outgoing,payload);
    for (size_t at=0;at<count;++at) {
        struct hop hop=queue[at];
        struct md_network_interface *link=hop.link;
        int result=visitor(context,hop.space,link,hop.outgoing,&hop.payload);
        if (result<0) { free(queue); return result; }
        if (result) continue;
        if (hop.outgoing && link->kind==MD_LINK_LOOPBACK) enqueue(queue,&count,hop.space,link,0,hop.payload);
        else if (hop.outgoing && link->kind==MD_LINK_VETH) {
            struct md_network_namespace *peer=md_network_interface_owner(hop.space->fabric,link->peer);
            if (peer) enqueue(queue,&count,peer,md_network_interface_find(peer,link->peer,NULL),0,hop.payload);
        } else if (link->kind==MD_LINK_BRIDGE || (!hop.outgoing && link->master)) {
            unsigned bridge=link->kind==MD_LINK_BRIDGE ? link->info.index : link->master;
            struct md_network_interface *master=md_network_interface_find(hop.space,bridge,NULL);
            if (!master || !(master->info.flags&IFF_UP)) continue;
            if (link->master) enqueue(queue,&count,hop.space,master,0,hop.payload);
            /* Ingress ports, not a second walk from the bridge master, own
             * flooding. This retains isolation/hairpin policy through cycles. */
            if ((link->kind!=MD_LINK_BRIDGE || hop.outgoing) && (!link_local || hop.outgoing))
                for (unsigned i=0;i<hop.space->count;++i) {
                    struct md_network_interface *port=&hop.space->links[i];
                    if (port->master==bridge && md_network_bridge_forward(link->kind==MD_LINK_BRIDGE ? NULL : link,port))
                        enqueue(queue,&count,hop.space,port,1,hop.payload);
                }
        }
    }
    free(queue); return 0;
}
int md_network_link_walk(struct md_network_namespace *space,struct md_network_interface *source,int link_local,
        md_network_link_visitor visitor,void *context,const void *payload) { return walk(space,source,link_local,visitor,context,payload,1); }
int md_network_link_ingress(struct md_network_namespace *space,struct md_network_interface *source,int link_local,
        md_network_link_visitor visitor,void *context,const void *payload) { return walk(space,source,link_local,visitor,context,payload,0); }
