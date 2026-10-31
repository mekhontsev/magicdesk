#ifndef MD_PROCFS_VIEW_H
#define MD_PROCFS_VIEW_H
#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>
struct md_filesystem;
struct md_view_mount;
struct md_mount_namespace;
struct md_provider_tree;
struct md_identity;
struct stat;
struct md_fs_anchor;
struct md_fs_request;
struct md_fs_result;
struct md_fs_output;
struct md_control_file;
int md_fs_proc_control_stat(struct md_filesystem *,struct md_control_file,struct stat *);
int md_fs_proc_task(struct md_filesystem *,struct md_fs_anchor *,const char *,
    const struct md_fs_request *,struct md_fs_result *,const struct md_fs_output *);
struct md_provider_tree *md_procfs_new(int parent,const char *options,const struct md_identity *);
/* One-time host capability admission: 1 available, 0 unavailable, negative errno. */
int md_procfs_pressure_probe(int proc_root);
int md_procfs_options(const struct md_view_mount *,const struct md_identity *,char *,size_t);
int md_procfs_check(struct md_filesystem *,const struct md_view_mount *,const char *path,unsigned minimum);
int md_procfs_path(const struct md_view_mount *,int,char *,size_t);
/* Descriptive filesystem-relative name, with this proc mount's PID numbers. */
int md_procfs_guest_path(const struct md_view_mount *,int,char *,size_t);
int md_procfs_noatime(struct md_filesystem *,int,const char *);
int md_procfs_ownership(struct md_filesystem *,const char *,struct stat *);
int md_procfs_masked(const char *path);
/* Only kernel-owned root links with immutable dentries may be reopened by name. */
const char *md_procfs_static_link(int descriptor);
/* Mount-scoped proc traversal. Native directory descriptors retain the process
 * dentry while the mount retains the namespace that names it. */
int md_procfs_step(struct md_filesystem *,struct md_mount_namespace *,struct md_view_mount *,int directory,
    const char *name,const char *tail,int follow_final,uint64_t resolve);
ssize_t md_procfs_readlink(struct md_filesystem *,const struct md_view_mount *,int,void *,size_t);
ssize_t md_procfs_getdents(struct md_filesystem *,const struct md_view_mount *,int,void *,size_t);
#endif
