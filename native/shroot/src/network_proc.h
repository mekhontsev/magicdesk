#ifndef MD_NETWORK_PROC_H
#define MD_NETWORK_PROC_H
#include "network_namespace.h"
enum md_network_table { MD_NET_TABLE_TCP, MD_NET_TABLE_TCP6, MD_NET_TABLE_UDP, MD_NET_TABLE_UDP6,
    MD_NET_TABLE_IF_INET6, MD_NET_TABLE_ROUTE, MD_NET_TABLE_ROUTE6 };
struct md_control_file md_network_proc_new(struct md_network_namespace *,enum md_network_table);
#endif
