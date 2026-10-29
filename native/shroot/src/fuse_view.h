#ifndef MD_FUSE_VIEW_H
#define MD_FUSE_VIEW_H
#include "fuse_session.h"
#include "provider_tree.h"
struct md_filesystem;
struct md_view_location;
struct md_mount_source;
struct md_fs_request;
struct md_fs_result;
struct md_fs_output;
struct md_inode_store;
int md_fuse_view_watch(struct md_provider_tree *,struct md_inode_store *,int instance,int object,unsigned flags);
int md_fuse_view_source(struct md_filesystem *,const struct md_fs_request *,struct md_mount_source *);
int md_fuse_view_matches(struct md_provider_tree *);
dev_t md_fuse_view_device(struct md_provider_tree *);
int md_fuse_view_options(struct md_provider_tree *,const struct md_identity *,char *,size_t);
const char *md_fuse_view_type(struct md_provider_tree *);
const char *md_fuse_view_label(struct md_provider_tree *);
int md_fs_fuse_operation(struct md_filesystem *,const struct md_fs_request *,
    const struct md_view_location *,const struct md_view_location *,struct md_fs_result *,const struct md_fs_output *);
#endif
