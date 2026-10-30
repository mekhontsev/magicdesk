#ifndef MD_NETWORK_NAMESPACE_H
#define MD_NETWORK_NAMESPACE_H
#include "fd_object.h"
#include "user_namespace.h"
#include "control_file.h"
#include "network_packet.h"
struct md_identity;
struct md_network_target;

/* Native launches retain Android networking. A routed launch owns a logical
 * fabric and socket-address translation. New namespaces start private, with
 * no route until explicitly attached; none of these modes alters Android links. */
struct md_network_namespace;
enum md_network_mode { MD_NETWORK_NATIVE, MD_NETWORK_PRIVATE, MD_NETWORK_ROUTED };
struct md_network_namespace *md_network_namespace_new(struct md_user_namespace *, enum md_network_mode);
struct md_network_namespace *md_network_namespace_copy(struct md_network_namespace *,struct md_user_namespace *);
struct md_network_namespace *md_network_namespace_retain(struct md_network_namespace *);
void md_network_namespace_release(struct md_network_namespace *);
uint64_t md_network_namespace_inode(const struct md_network_namespace *);
const struct md_user_namespace *md_network_namespace_owner(const struct md_network_namespace *);
int md_network_namespace_managed(const struct md_network_namespace *);
enum md_network_parameter { MD_NET_UNPRIVILEGED_PORT, MD_NET_PING_GROUPS, MD_NET_LOCAL_PORTS,
    MD_NET_IPV4_FORWARD, MD_NET_ROUTE_LOCALNET, MD_NET_RP_FILTER, MD_NET_ARP_NOTIFY,
    MD_NET_BRIDGE_FILTER, MD_NET_TCP_KEEPIDLE, MD_NET_TCP_KEEPINTVL, MD_NET_TCP_KEEPCNT,
    MD_NET_IPV6_FORWARD, MD_NET_IPV6_DISABLE, MD_NET_IPV6_BINDONLY, MD_NET_BRIDGE_FILTER6,
    MD_NET_IPV6_DAD, MD_NET_IPV6_RA, MD_NET_IPV6_MTU, MD_NET_IPV6_HOPS,
    MD_NET_IPV6_ADDRGEN, MD_NET_IPV6_PRIVACY };
#define MD_NET_SCOPE_ALL UINT64_MAX
#define MD_NET_SCOPE_DEFAULT (UINT64_MAX-1)
/* Resolve a policy lifetime once at open. Rename retains it; namespace moves
 * and deletion retire it even if the same interface later returns. */
int md_network_parameter_scope(struct md_network_namespace *,enum md_network_parameter,const char *,uint64_t *);
int md_network_parameter_read(struct md_network_namespace *,enum md_network_parameter,uint64_t,uint32_t values[2]);
int md_network_parameter_write(struct md_network_namespace *,enum md_network_parameter,uint64_t,const uint32_t values[2]);
/* Policy state belongs to the selected managed namespace, never Android. */
struct md_control_file md_network_sysctl_new(struct md_network_namespace *,enum md_network_parameter,const char *,struct md_fs_anchor *);
enum md_network_link_kind { MD_LINK_LOOPBACK, MD_LINK_UPLINK, MD_LINK_BRIDGE, MD_LINK_VETH, MD_LINK_TUN, MD_LINK_TAP };
struct md_network_link {
    enum md_network_link_kind kind;
    unsigned index, flags, mtu, type, state, tx_queue_length;
    unsigned peer;
    uint64_t lifetime;
    char name[16];
    unsigned char address[6];
};
/* One control-plane snapshot for ioctl, netlink and namespace-mounted sysfs. */
int md_network_link_info(const struct md_network_namespace *, unsigned, struct md_network_link *);
int md_network_link_list(struct md_network_namespace *,struct md_network_link *,unsigned capacity);
/* Zero means unchanged; publish the generation only after consuming the snapshot. */
int md_network_link_snapshot(struct md_network_namespace *,uint64_t *generation,
    struct md_network_link *,unsigned capacity);
struct md_fd_object *md_network_socket_new(struct md_network_namespace *);
int md_network_socket_offline(const struct md_fd_object *);
int md_network_socket_control(const struct md_fd_object *);
struct md_network_namespace *md_network_socket_namespace(const struct md_fd_object *);
int md_network_interface_ioctl(unsigned long);
int md_network_netlink_destination(const struct md_network_send *,uint32_t *groups);
int md_network_socket_open(struct md_network_namespace *, const struct md_identity *, int family, int type, int protocol,
    struct md_fd_object **, int *fd);
long md_network_socket_call(struct md_fd_object *, const struct md_identity *, long nr,
    const unsigned long *, void *data, size_t capacity, size_t size, pid_t pid,const struct md_network_send *,
    const struct md_pid_identity *,
    const struct md_network_target *);
#endif
