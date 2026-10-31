#ifndef MD_NETWORK_IOCTL_H
#define MD_NETWORK_IOCTL_H
#include <linux/sockios.h>
#include <linux/if_bridge.h>
#include <linux/if_arp.h>
#include <net/if.h>
#include <net/route.h>
#include <linux/ipv6.h>
#include <linux/ipv6_route.h>
struct md_network_route_ioctl { struct rtentry route; char device[IFNAMSIZ]; };
struct md_network_bridge_ioctl { char name[IFNAMSIZ]; unsigned long args[4]; struct __bridge_info info; };
struct md_network_ifconf { int capacity, length; struct ifreq entries[]; };
static inline int md_network_route_ioctl(unsigned long command) { return command==SIOCADDRT || command==SIOCDELRT; }
static inline int md_network_arp_ioctl(unsigned long command) { return command==SIOCSARP || command==SIOCDARP || command==SIOCGARP; }
static inline int md_network_address_ioctl(unsigned long command) {
    return command==SIOCGIFADDR || command==SIOCSIFADDR || command==SIOCGIFNETMASK
        || command==SIOCSIFNETMASK || command==SIOCGIFDSTADDR || command==SIOCSIFDSTADDR;
}
static inline int md_network_family_ioctl(unsigned long command) {
    return command==SIOCSIFADDR || command==SIOCDIFADDR || md_network_route_ioctl(command);
}
#endif
