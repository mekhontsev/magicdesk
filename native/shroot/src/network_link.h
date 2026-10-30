#ifndef MD_NETWORK_LINK_H
#define MD_NETWORK_LINK_H
struct md_network_namespace;
struct md_network_interface;
typedef void (*md_network_link_visitor)(void *,struct md_network_namespace *,struct md_network_interface *,int);
/* A bounded L2 traversal under the fabric lock. Each interface/direction is
 * visited once, including bridge masters; cycles cannot recurse or multiply IO. */
int md_network_link_walk(struct md_network_namespace *,struct md_network_interface *,int,md_network_link_visitor,void *);
int md_network_link_ingress(struct md_network_namespace *,struct md_network_interface *,int,md_network_link_visitor,void *);
#endif
