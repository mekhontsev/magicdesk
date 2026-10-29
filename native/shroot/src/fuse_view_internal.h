#ifndef MD_FUSE_VIEW_INTERNAL_H
#define MD_FUSE_VIEW_INTERNAL_H
#include "fuse_view.h"
#include "fs_mount_internal.h"
#include "watch_source.h"
#include "resource_order.h"
#include "fuse_mapping.h"
#include <pthread.h>
#include <stdatomic.h>

/* One mount owns nodes and their private carriers. Files retain this owner; mutable
 * node metadata and mapping generations are protected by its lock. */
enum { NODE_BUCKETS=1024, NODE_LIMIT=65536 };
struct fuse_node {
    struct fuse_node *id_next,*fd_next;
    struct fuse_name *names;
    uint64_t id,generation,lookups,changes;
    uint64_t attributes_epoch;
    int64_t attributes_until;
    mode_t type;
    char carrier_name[32];
    struct stat carrier,attributes;
    struct md_resource_queue *writes;
    struct md_fuse_mapping *mapping;
    struct fuse_snapshot *snapshot;
    unsigned opened;
    int deleted;
};
struct fuse_name {
    struct fuse_name *next,*node_next;
    struct fuse_node *parent,*node;
    unsigned references;
    int detached;
    uint64_t epoch;
    int64_t valid_until;
    char name[NAME_MAX+1];
};
struct fuse_view {
    atomic_int no_flush;
    atomic_uint references;
    pthread_mutex_t lock;
    struct md_fuse_session *session;
    struct md_fuse_connection *notifications;
    struct md_user_namespace *user_namespace;
    struct md_pid_namespace *pid_namespace;
    dev_t device;
    char *type,*source;
    int root;
    unsigned count;
    struct fuse_node *by_id[NODE_BUCKETS],*by_fd[NODE_BUCKETS],*root_node;
    struct fuse_name *names[NODE_BUCKETS];
    struct md_watch_source *events;
    unsigned rename_cookie;
    uint64_t name_epoch;
    struct fuse_snapshot *images_first,*images_last;
    uint64_t images_bytes;
    unsigned images_count;
};
uint64_t md_fuse_snapshot_open(struct fuse_view *,struct fuse_node *,unsigned flags);
int md_fuse_snapshot_get(struct fuse_view *,struct fuse_node *,const struct fuse_attr *,uint64_t changes);
int md_fuse_snapshot_store(struct fuse_view *,struct fuse_node *,const struct fuse_attr *,uint64_t changes,int fd);
void md_fuse_snapshot_clear(struct fuse_view *);
void *md_fuse_view_retain(void *);
void md_fuse_view_release(void *);
int md_fuse_view_attributes(struct fuse_view *,const struct fuse_attr *,struct stat *);
uint32_t md_fuse_view_pid(struct fuse_view *,pid_t);
int md_fuse_node_open(struct fuse_view *,struct fuse_node *,int flags);
void md_fuse_file_release_remote(struct fuse_view *,uint64_t,int,const struct md_identity *,uint64_t,unsigned);
void md_fuse_file_release_handle(struct fuse_view *,struct fuse_node *,const struct md_identity *,uint64_t,unsigned);
/* Takes the remote open handle on both success and failure. */
int md_fuse_file_publish(struct fuse_view *,struct fuse_node *,struct md_fs_anchor *,
    struct fuse_node *parent,const char *name,const struct md_identity *,unsigned,
    const struct fuse_open_out *,struct md_fs_result *,const struct md_fs_output *);
struct fuse_name *md_fuse_name_retain(struct fuse_view *,struct fuse_node *,struct fuse_node *,const char *);
void md_fuse_name_release(struct fuse_view *,struct fuse_name *);
void md_fuse_file_event(struct fuse_view *,struct fuse_node *,struct fuse_name *,unsigned mask);
int md_fuse_node_write_begin(struct fuse_view *,struct fuse_node *,struct md_resource_order *,struct md_resource_wakeup *);
void md_fuse_node_write_end(struct md_resource_order *);
void md_fuse_node_closed(struct fuse_view *,struct fuse_node *);
#endif
