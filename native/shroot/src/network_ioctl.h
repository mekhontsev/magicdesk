#ifndef MD_NETWORK_IOCTL_H
#define MD_NETWORK_IOCTL_H
#include <linux/sockios.h>
#include <net/if.h>
#include <net/route.h>
struct md_network_route_ioctl { struct rtentry route; char device[IFNAMSIZ]; };
static inline int md_network_route_ioctl(unsigned long command) { return command==SIOCADDRT || command==SIOCDELRT; }
#endif
