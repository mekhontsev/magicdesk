#ifndef MD_NETWORK_LINK_H
#define MD_NETWORK_LINK_H
struct md_network_namespace;
struct md_network_interface;
/* The visitor may replace a branch's borrowed payload. Replacements remain
 * alive until traversal ends. Zero continues, positive drops this branch,
 * negative aborts traversal; siblings retain their original payload. */
typedef int (*md_network_link_visitor)(void *,struct md_network_namespace *,struct md_network_interface *,int,const void **);
/* A bounded L2 traversal under the fabric lock. Each interface/direction is
 * visited once, including bridge masters; cycles cannot recurse or multiply IO. */
int md_network_link_walk(struct md_network_namespace *,struct md_network_interface *,int,md_network_link_visitor,void *,const void *);
int md_network_link_ingress(struct md_network_namespace *,struct md_network_interface *,int,md_network_link_visitor,void *,const void *);
#endif
