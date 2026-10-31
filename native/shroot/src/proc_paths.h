#ifndef MD_PROC_PATHS_H
#define MD_PROC_PATHS_H
#include <stddef.h>
#include <stdint.h>

enum md_proc_kind { MD_PROC_NONE, MD_PROC_FD, MD_PROC_FDINFO, MD_PROC_CWD, MD_PROC_ROOT, MD_PROC_EXE,
                    MD_PROC_CMDLINE, MD_PROC_AUXV, MD_PROC_FOREIGN, MD_PROC_MOUNTS, MD_PROC_MOUNTINFO,
                    MD_PROC_UID_MAP, MD_PROC_GID_MAP, MD_PROC_SETGROUPS,
                    MD_PROC_HOSTNAME, MD_PROC_DOMAINNAME, MD_PROC_OSTYPE,
                    MD_PROC_OSRELEASE, MD_PROC_VERSION,
                    MD_PROC_OVERFLOWUID, MD_PROC_OVERFLOWGID, MD_PROC_CAP_LAST,
                    MD_PROC_NR_OPEN, MD_PROC_PID_MAX,
                    MD_PROC_INOTIFY_INSTANCES, MD_PROC_INOTIFY_WATCHES, MD_PROC_INOTIFY_QUEUE,
                    MD_PROC_NS_USER, MD_PROC_NS_MOUNT, MD_PROC_NS_UTS, MD_PROC_NS_IPC,
                    MD_PROC_NS_PID, MD_PROC_NS_PID_CHILDREN, MD_PROC_NS_NETWORK, MD_PROC_NS_CGROUP,
                    MD_PROC_NS_DIRECTORY,
                    MD_PROC_PROCESS_DIRECTORY,
                    MD_PROC_STATUS, MD_PROC_STAT, MD_PROC_LIMITS,
                    MD_PROC_CGROUP, MD_PROC_CGROUPS,
                    MD_PROC_NS_UNSUPPORTED,
                    MD_PROC_NET_UNPRIVILEGED_PORT, MD_PROC_NET_PING_GROUPS, MD_PROC_NET_LOCAL_PORTS,
                    MD_PROC_NET_IPV4_FORWARD, MD_PROC_NET_ROUTE_LOCALNET, MD_PROC_NET_RP_FILTER, MD_PROC_NET_ARP_NOTIFY,
                    MD_PROC_NET_BRIDGE_FILTER,
                    MD_PROC_NET_TCP_KEEPIDLE, MD_PROC_NET_TCP_KEEPINTVL, MD_PROC_NET_TCP_KEEPCNT,
                    MD_PROC_NET_IPV6_FORWARD, MD_PROC_NET_IPV6_DISABLE, MD_PROC_NET_IPV6_BINDONLY, MD_PROC_NET_BRIDGE_FILTER6, MD_PROC_NET_IPV6_DAD, MD_PROC_NET_IPV6_RA,
                    MD_PROC_NET_IPV6_AUTOCONF, MD_PROC_NET_IPV6_MTU, MD_PROC_NET_IPV6_HOPS, MD_PROC_NET_IPV6_ADDRGEN, MD_PROC_NET_IPV6_PRIVACY,
                    MD_PROC_MEMORY, MD_PROC_MAX_USER_NAMESPACES,
                    MD_PROC_NET_TCP, MD_PROC_NET_TCP6, MD_PROC_NET_UDP, MD_PROC_NET_UDP6, MD_PROC_NET_IF_INET6,
                    MD_PROC_DEVICES, MD_PROC_KERNEL_CMDLINE, MD_PROC_FILESYSTEMS };
static inline int md_proc_static(enum md_proc_kind kind) {
    return kind==MD_PROC_DEVICES || kind==MD_PROC_KERNEL_CMDLINE || kind==MD_PROC_FILESYSTEMS;
}
static inline int md_proc_network_table(enum md_proc_kind kind) {
    return kind>=MD_PROC_NET_TCP && kind<=MD_PROC_NET_IF_INET6;
}
static inline int md_proc_network_control(enum md_proc_kind kind) {
    return (kind>=MD_PROC_NET_UNPRIVILEGED_PORT && kind<=MD_PROC_NET_IPV6_PRIVACY)
        || md_proc_network_table(kind);
}
static inline int md_proc_network_sysctl(enum md_proc_kind kind) {
    return kind>=MD_PROC_NET_UNPRIVILEGED_PORT && kind<=MD_PROC_NET_IPV6_PRIVACY;
}
static inline int md_proc_network_interface(enum md_proc_kind kind) {
    return (kind>=MD_PROC_NET_ROUTE_LOCALNET && kind<=MD_PROC_NET_ARP_NOTIFY)
        || kind==MD_PROC_NET_IPV6_FORWARD || kind==MD_PROC_NET_IPV6_DISABLE
        || (kind>=MD_PROC_NET_IPV6_DAD && kind<=MD_PROC_NET_IPV6_PRIVACY);
}
/* Terminal proc objects, never directories or magic links. A mounted proc
 * walker resolves their entire path; a remaining suffix is always ENOTDIR. */
static inline int md_proc_regular(enum md_proc_kind kind) {
    return (kind>=MD_PROC_UID_MAP && kind<=MD_PROC_INOTIFY_QUEUE)
        || (kind>=MD_PROC_STATUS && kind<=MD_PROC_CGROUPS)
        || md_proc_network_control(kind) || kind==MD_PROC_MAX_USER_NAMESPACES || md_proc_static(kind)
        || kind==MD_PROC_FDINFO || kind==MD_PROC_CMDLINE || kind==MD_PROC_AUXV
        || kind==MD_PROC_MOUNTS || kind==MD_PROC_MOUNTINFO;
}
struct md_proc_path {
    enum md_proc_kind kind;
    size_t anchor_length;
    const char *tail;
    int ordinary_link;
    int process;
    int descriptor;
    enum md_proc_kind foreign_kind;
    char interface[16];
};
int md_host_path(const char *);
/* Classifies adapter-owned absolute strings, without resolving guest symlinks. */
struct md_proc_path md_proc_path(const char *);
struct md_proc_path md_proc_path_for(const char *, int tid, int tgid);
/* Pin thread-self to the requesting task before delegating a kernel lookup. */
int md_proc_task_path(const char *, char *, size_t);
/* An image belongs to one address space, not to the syscall transport. */
struct md_process_image {
    const void *arguments;
    size_t argument_bytes;
    size_t environment_bytes;
    unsigned char auxv[4096];
    size_t auxv_bytes;
    char executable_object[33];
    const char *executable_path;
};
void md_proc_image_init(struct md_process_image *, const void *arguments, size_t argument_bytes, size_t environment_bytes,
        const void *auxv, size_t auxv_bytes);
struct md_fs;
long md_proc_executable_open(const struct md_fs *, const char *path, int flags);
long md_proc_image_open(const struct md_fs *, enum md_proc_kind, int flags);
long md_proc_executable_capture(const struct md_fs *,int process,int flags);
#endif
