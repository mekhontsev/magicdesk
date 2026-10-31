#define _GNU_SOURCE
#include "proc_paths.h"
#include "raw.h"
#include <limits.h>

static const char *component(const char *p) {
    for (;;) {
        while (*p == '/') ++p;
        if (p[0] != '.' || (p[1] && p[1] != '/')) return p;
        ++p;
    }
}
static int word(const char **p, const char *name) {
    const char *s = component(*p);
    size_t n = md_length(name);
    if (!md_prefix(s, name) || (s[n] && s[n] != '/')) return 0;
    *p = s + n;
    return 1;
}
int md_host_path(const char *p) {
    if (*p != '/') return 0;
    if (word(&p, "dev")) return !word(&p, "shm");
    return word(&p, "proc");
}
static long number(const char **p) {
    const char *s = component(*p);
    if (*s < '0' || *s > '9') return -1;
    if (*s == '0' && s[1] >= '0' && s[1] <= '9') return -1;
    unsigned long n = 0;
    while (*s >= '0' && *s <= '9') {
        n = n * 10 + (unsigned)(*s++ - '0');
        if (n > INT_MAX) return -1;
    }
    if (*s && *s != '/') return -1;
    *p = s;
    return (long)n;
}
static enum md_proc_kind network_table(const char **p) {
    if (word(p,"tcp")) return MD_PROC_NET_TCP;
    if (word(p,"tcp6")) return MD_PROC_NET_TCP6;
    if (word(p,"udp")) return MD_PROC_NET_UDP;
    if (word(p,"udp6")) return MD_PROC_NET_UDP6;
    if (word(p,"if_inet6")) return MD_PROC_NET_IF_INET6;
    return MD_PROC_NONE;
}
struct md_proc_path md_proc_path_for(const char *path,int tid,int tgid) {
    struct md_proc_path out = {.descriptor=-1};
    if (*path != '/') return out;
    const char *p = path;
    int foreign = 0;
    if (word(&p, "dev")) {
        if (word(&p, "fd")) {
            out.descriptor=(int)number(&p);
            if (out.descriptor < 0) return out;
        } else if (word(&p, "stdin")) { out.ordinary_link=1; out.descriptor=0; }
        else if (word(&p, "stdout")) { out.ordinary_link=1; out.descriptor=1; }
        else if (word(&p, "stderr")) { out.ordinary_link=1; out.descriptor=2; }
        else return out;
        out.process=tgid;
        out.kind = MD_PROC_FD;
    } else if (word(&p, "proc")) {
        if (word(&p,"filesystems")) {
            out.kind=MD_PROC_FILESYSTEMS; out.process=tid;
            out.tail=p; out.anchor_length=(size_t)(p-path); return out;
        }
        if (word(&p,"devices")) {
            out.kind=MD_PROC_DEVICES; out.process=tid;
            out.tail=p; out.anchor_length=(size_t)(p-path); return out;
        }
        if (word(&p,"cmdline")) {
            out.kind=MD_PROC_KERNEL_CMDLINE; out.process=tid;
            out.tail=p; out.anchor_length=(size_t)(p-path); return out;
        }
        if (word(&p,"net")) {
            out.ordinary_link=!*p;
            out.kind=network_table(&p); out.process=tid;
            out.tail=p; out.anchor_length=(size_t)(p-path); return out;
        }
        if (word(&p,"cgroups")) {
            out.kind=MD_PROC_CGROUPS; out.process=tid;
            out.tail=p; out.anchor_length=(size_t)(p-path); return out;
        }
        if (word(&p,"mounts")) {
            out.ordinary_link=!*p;
            out.kind=MD_PROC_MOUNTS; out.tail=p; out.anchor_length=(size_t)(p-path); return out;
        }
        if (word(&p,"sys")) {
            if (word(&p,"fs")) {
                if (word(&p,"nr_open")) {
                    out.kind=MD_PROC_NR_OPEN;
                    out.process=tid; out.tail=p; out.anchor_length=(size_t)(p-path); return out;
                }
                if (!word(&p,"inotify")) return out;
                if (word(&p,"max_user_instances")) out.kind=MD_PROC_INOTIFY_INSTANCES;
                else if (word(&p,"max_user_watches")) out.kind=MD_PROC_INOTIFY_WATCHES;
                else if (word(&p,"max_queued_events")) out.kind=MD_PROC_INOTIFY_QUEUE;
                out.process=tid; out.tail=p; out.anchor_length=(size_t)(p-path); return out;
            }
            if (word(&p,"user")) {
                if (word(&p,"max_user_namespaces")) out.kind=MD_PROC_MAX_USER_NAMESPACES;
                out.process=tid; out.tail=p; out.anchor_length=(size_t)(p-path); return out;
            }
            if (word(&p,"net")) {
                if (word(&p,"bridge")) {
                    if (word(&p,"bridge-nf-call-iptables")) out.kind=MD_PROC_NET_BRIDGE_FILTER;
                    else if (word(&p,"bridge-nf-call-ip6tables")) out.kind=MD_PROC_NET_BRIDGE_FILTER6;
                    out.process=tid; out.tail=p; out.anchor_length=(size_t)(p-path); return out;
                }
                if (word(&p,"ipv6")) {
                    if (word(&p,"bindv6only")) out.kind=MD_PROC_NET_IPV6_BINDONLY;
                    else if (word(&p,"conf")) {
                        const char *name=component(p);
                        size_t length=0;
                        while (name[length] && name[length]!='/') ++length;
                        if (!length || length>=sizeof(out.interface)) return out;
                        p=name+length;
                        if (word(&p,"forwarding")) out.kind=MD_PROC_NET_IPV6_FORWARD;
                        else if (word(&p,"disable_ipv6")) out.kind=MD_PROC_NET_IPV6_DISABLE;
                        else if (word(&p,"accept_dad")) out.kind=MD_PROC_NET_IPV6_DAD;
                        else if (word(&p,"accept_ra")) out.kind=MD_PROC_NET_IPV6_RA;
                        else if (word(&p,"autoconf")) out.kind=MD_PROC_NET_IPV6_AUTOCONF;
                        else if (word(&p,"mtu")) out.kind=MD_PROC_NET_IPV6_MTU;
                        else if (word(&p,"hop_limit")) out.kind=MD_PROC_NET_IPV6_HOPS;
                        else if (word(&p,"addr_gen_mode")) out.kind=MD_PROC_NET_IPV6_ADDRGEN;
                        else if (word(&p,"use_tempaddr")) out.kind=MD_PROC_NET_IPV6_PRIVACY;
                        else return out;
                        memcpy(out.interface,name,length);
                    } else return out;
                    out.process=tid; out.tail=p; out.anchor_length=(size_t)(p-path); return out;
                }
                if (!word(&p,"ipv4")) return out;
                if (word(&p,"ip_unprivileged_port_start")) out.kind=MD_PROC_NET_UNPRIVILEGED_PORT;
                else if (word(&p,"ping_group_range")) out.kind=MD_PROC_NET_PING_GROUPS;
                else if (word(&p,"ip_local_port_range")) out.kind=MD_PROC_NET_LOCAL_PORTS;
                else if (word(&p,"ip_forward")) out.kind=MD_PROC_NET_IPV4_FORWARD;
                else if (word(&p,"tcp_keepalive_time")) out.kind=MD_PROC_NET_TCP_KEEPIDLE;
                else if (word(&p,"tcp_keepalive_intvl")) out.kind=MD_PROC_NET_TCP_KEEPINTVL;
                else if (word(&p,"tcp_keepalive_probes")) out.kind=MD_PROC_NET_TCP_KEEPCNT;
                else if (word(&p,"conf")) {
                    const char *name=component(p);
                    size_t length=0;
                    while (name[length] && name[length]!='/') ++length;
                    if (!length || length>=sizeof(out.interface)) return out;
                    p=name+length;
                    if (word(&p,"route_localnet")) out.kind=MD_PROC_NET_ROUTE_LOCALNET;
                    else if (word(&p,"rp_filter")) out.kind=MD_PROC_NET_RP_FILTER;
                    else if (word(&p,"arp_notify")) out.kind=MD_PROC_NET_ARP_NOTIFY;
                    else return out;
                    memcpy(out.interface,name,length);
                }
                else return out;
                out.process=tid;
                out.tail=p; out.anchor_length=(size_t)(p-path); return out;
            }
            if (!word(&p,"kernel")) return out;
            if (word(&p,"hostname")) out.kind=MD_PROC_HOSTNAME;
            else if (word(&p,"domainname")) out.kind=MD_PROC_DOMAINNAME;
            else if (word(&p,"ostype")) out.kind=MD_PROC_OSTYPE;
            else if (word(&p,"osrelease")) out.kind=MD_PROC_OSRELEASE;
            else if (word(&p,"version")) out.kind=MD_PROC_VERSION;
            else if (word(&p,"overflowuid")) out.kind=MD_PROC_OVERFLOWUID;
            else if (word(&p,"overflowgid")) out.kind=MD_PROC_OVERFLOWGID;
            else if (word(&p,"cap_last_cap")) out.kind=MD_PROC_CAP_LAST;
            else if (word(&p,"pid_max")) out.kind=MD_PROC_PID_MAX;
            else return out;
            out.process=tid;
            out.tail=p; out.anchor_length=(size_t)(p-path); return out;
        }
        if (word(&p,"self")) { out.process=tgid; out.ordinary_link=!*p; }
        else if (word(&p,"thread-self")) { out.process=tid; out.ordinary_link=!*p; }
        else {
            long pid = number(&p);
            if (pid < 0) return out;
            foreign = pid != tgid && pid != tid;
            out.process = (int)pid;
        }
        if (word(&p, "task")) {
            /* Only the group leader's proc directory contains task/. */
            if (out.process == tid && out.process != tgid) return out;
            long selected = number(&p);
            if (selected < 0) return out;
            foreign |= selected != tid;
            out.process = (int)selected;
        }
        if (!*component(p)) { out.kind=MD_PROC_PROCESS_DIRECTORY; p=component(p); }
        else if (word(&p, "fd")) {
            out.descriptor=(int)number(&p);
            if (out.descriptor < 0) return out;
            out.kind = MD_PROC_FD;
        } else if (word(&p, "fdinfo")) {
            out.descriptor=(int)number(&p);
            if (out.descriptor < 0) return out;
            out.kind=MD_PROC_FDINFO;
        } else if (word(&p, "cwd")) out.kind = MD_PROC_CWD;
        else if (word(&p, "root")) out.kind = MD_PROC_ROOT;
        else if (word(&p, "exe")) out.kind = MD_PROC_EXE;
        else if (word(&p, "cmdline")) out.kind = MD_PROC_CMDLINE;
        else if (word(&p, "auxv")) out.kind = MD_PROC_AUXV;
        else if (word(&p, "mem")) out.kind = MD_PROC_MEMORY;
        else if (word(&p,"status")) out.kind=MD_PROC_STATUS;
        else if (word(&p,"stat")) out.kind=MD_PROC_STAT;
        else if (word(&p,"limits")) out.kind=MD_PROC_LIMITS;
        else if (word(&p,"cgroup")) out.kind=MD_PROC_CGROUP;
        else if (word(&p,"net")) out.kind=network_table(&p);
        else if (word(&p, "mounts")) out.kind = MD_PROC_MOUNTS;
        else if (word(&p, "mountinfo")) out.kind = MD_PROC_MOUNTINFO;
        else if (word(&p, "uid_map")) out.kind = MD_PROC_UID_MAP;
        else if (word(&p, "gid_map")) out.kind = MD_PROC_GID_MAP;
        else if (word(&p, "setgroups")) out.kind = MD_PROC_SETGROUPS;
        else if (word(&p,"ns")) {
            if (word(&p,"user")) out.kind=MD_PROC_NS_USER;
            else if (word(&p,"mnt")) out.kind=MD_PROC_NS_MOUNT;
            else if (word(&p,"uts")) out.kind=MD_PROC_NS_UTS;
            else if (word(&p,"ipc")) out.kind=MD_PROC_NS_IPC;
            else if (word(&p,"pid")) out.kind=MD_PROC_NS_PID;
            else if (word(&p,"pid_for_children")) out.kind=MD_PROC_NS_PID_CHILDREN;
            else if (word(&p,"net")) out.kind=MD_PROC_NS_NETWORK;
            else if (word(&p,"cgroup")) out.kind=MD_PROC_NS_CGROUP;
            else if (*component(p)) { out.kind=MD_PROC_NS_UNSUPPORTED; p+=md_length(p); }
            else { out.kind=MD_PROC_NS_DIRECTORY; p=component(p); }
        }
        else return out;
        if (foreign) { out.foreign_kind=out.kind; out.kind = MD_PROC_FOREIGN; }
    } else return out;
    out.anchor_length = (size_t)(p - path);
    out.tail = p;
    return out;
}
struct md_proc_path md_proc_path(const char *path) {
    return md_proc_path_for(path,(int)RAW0(gettid),(int)RAW0(getpid));
}
int md_proc_task_path(const char *path, char *out, size_t capacity) {
    const char *p=path;
    if (*p!='/' || !word(&p,"proc") || !word(&p,"thread-self"))
        return md_copy(out,capacity,path);
    char prefix[64]="/proc/";
    md_decimal(prefix+md_length(prefix),(unsigned)RAW0(gettid));
    int error=md_copy(out,capacity,prefix);
    return error ? error : md_append(out,capacity,p);
}
