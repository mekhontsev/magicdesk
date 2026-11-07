#ifndef MD_MOUNT_INSTANCE_H
#define MD_MOUNT_INSTANCE_H
#include "mount_policy.h"
#include "mount_propagation.h"
#include "control_file.h"
#include <stdatomic.h>
#include <stdint.h>
#include <sys/stat.h>

struct md_inode_lifetime;
enum md_mount_backing { MD_MOUNT_NATIVE, MD_MOUNT_INODE, MD_MOUNT_PROC, MD_MOUNT_DEVPTS, MD_MOUNT_CGROUP, MD_MOUNT_SYSFS, MD_MOUNT_MQUEUE, MD_MOUNT_PROVIDER };
/* Stable mount identity, independently of its placement or worker connection. */
struct md_view_mount {
    int source, target;
    struct md_mount_policy policy;
    struct md_mount_links links;
    unsigned id;
    uint64_t generation;
    enum md_mount_backing backing;
    struct md_inode_lifetime *storage;
    struct md_pid_namespace *pid_namespace;
    struct md_devpts *devpts;
    struct md_cgroup_view *cgroup;
    struct md_sysfs *sysfs;
    struct md_provider_tree *provider;
    struct md_ipc_namespace *ipc_namespace;
    struct md_control_file file;
    int detached, locked;
    /* Host carriers admitted locally but not transferable through SCM_RIGHTS. */
    int local_custody;
    /* Implicit launch providers retained by a root moved through pivot_root. */
    int root_providers;
    atomic_uint anchors;
    struct md_view_mount *parent;
    struct stat native, guest;
};
/* Caller has retired all anchors and placement references. Also accepts a
 * partially prepared instance; preserve its generation when retiring a slot. */
void md_view_mount_close(struct md_view_mount *);
/* Independent instance and policy; providers and propagation retain identity.
 * Failure leaves a closeable candidate and never changes the source. */
int md_view_mount_clone(struct md_view_mount *,const struct md_view_mount *,int target,struct md_view_mount *parent);
int md_view_mount_prepare_transport(struct md_view_mount *);
#endif
