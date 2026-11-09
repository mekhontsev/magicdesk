#ifndef MD_SYSFS_VIEW_H
#define MD_SYSFS_VIEW_H
#include "network_namespace.h"
#include "control_file.h"

struct md_sysfs;
struct md_provider_tree;
/* Shared native CPU/NUMA projection; device/network inventories stay virtual. */
int md_sysfs_host_visible(const char *path);
/* A mount retains its network view, not the caller's current namespace. */
struct md_sysfs *md_sysfs_new(int parent,struct md_network_namespace *,const struct md_identity *);
struct md_sysfs *md_sysfs_retain(struct md_sysfs *);
void md_sysfs_release(struct md_sysfs *);
int md_sysfs_root(const struct md_sysfs *);
struct md_provider_tree *md_sysfs_provider(const struct md_sysfs *);
/* Owned metadata values survive interface rename, removal and namespace moves. */
struct md_sysfs_node {
    struct stat stat;
    unsigned attribute,interface;
    uint64_t lifetime, bridge_lifetime;
};
int md_sysfs_refresh(struct md_sysfs *);
/* -EXDEV selects native lower-tree handling. */
int md_sysfs_node(struct md_sysfs *,int fd,struct md_sysfs_node *);
int md_sysfs_node_open(struct md_sysfs *,const struct md_sysfs_node *,int flags,const struct md_identity *,
    struct md_fs_anchor *,const struct md_control_file_ops **,void **);
#endif
