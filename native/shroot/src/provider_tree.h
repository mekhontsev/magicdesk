#ifndef MD_PROVIDER_TREE_H
#define MD_PROVIDER_TREE_H
#include "control_file.h"
#include "mount_policy.h"
#include <sys/types.h>
struct md_provider_tree;
struct md_user_namespace;
/* Superblock policy is shared by aliases; mount policy remains independent.
 * Imported policies are read-only observers of the original owner. */
void md_provider_tree_set_owner(struct md_provider_tree *,const struct md_user_namespace *);
struct md_user_namespace *md_provider_tree_owner(const struct md_provider_tree *);
struct md_mount_policy *md_provider_tree_super_policy(struct md_provider_tree *);
unsigned md_provider_tree_super_flags(const struct md_provider_tree *);
/* One admitted lookup. Completion transfers an O_PATH descriptor; destroying
 * a pending lookup cancels it. No borrowed actor or output survives admission. */
struct md_provider_lookup {
    int (*ready)(struct md_provider_lookup *);
    int (*complete)(struct md_provider_lookup *);
    void (*destroy)(struct md_provider_lookup *);
    /* Optional caller-affine input, serviced only by an admitting transport.
     * Metadata describes the same captured FD when external getattr is denied. */
    struct md_fs_task_input *task_input;
    const struct stat *metadata;
};
struct md_provider_tree_ops {
    void *(*retain)(void *);
    void (*release)(void *);
    /* Return 1 with an owned control, 0 for an ordinary node, or -errno. */
    int (*control)(void *,int fd,struct md_control_file *);
    unsigned long filesystem;
    /* Optional lower-tree projection, including ancestor directories. Upper
     * nodes remain independently visible. Called only for path discovery. */
    int (*lower_visible)(const char *path);
    /* Ordinary nodes only; typed control files retain their own IO policy. */
    int readonly;
    int (*lookup)(void *,int directory,const char *,const struct md_control_actor *,
        struct md_provider_lookup **);
    int (*path)(void *,int fd,char *,size_t);
    int (*readonly_node)(void *,int fd);
};
/* A retained private provider over a read-through host tree. Opaque subtrees
 * and the optional visibility policy hide lower entries. Admitted lookup stays
 * live, including sysfs entries added after launch. No host file is copied. */
struct md_provider_tree *md_provider_tree_new(int parent,int lower,const char *const *opaque,
    unsigned opaque_count,const struct md_provider_tree_ops *,void *);
/* Retain a validated transient-root capability and its kernel lease. The
 * caller selects the exact projection; process-local controls are not imported. */
struct md_provider_tree *md_provider_tree_import(int upper,int lower,const struct md_provider_tree_ops *);
struct md_provider_tree *md_provider_tree_import_context(int upper,int lower,const struct md_provider_tree_ops *,void *);
int md_provider_tree_matches(const struct md_provider_tree *,const struct md_provider_tree_ops *);
struct md_provider_tree *md_provider_tree_retain(struct md_provider_tree *);
void md_provider_tree_release(struct md_provider_tree *);
int md_provider_tree_root(struct md_provider_tree *);
int md_provider_tree_step(struct md_provider_tree *,int directory,const char *);
/* Select the retained host tree without exposing another namespace's private
 * attachment points. The same path, visibility and cursor rules still apply. */
int md_provider_tree_host_step(struct md_provider_tree *,int directory,const char *);
int md_provider_tree_lookup(struct md_provider_tree *,int directory,const char *,
    const struct md_control_actor *,struct md_provider_lookup **);
int md_provider_tree_path(struct md_provider_tree *,int fd,char *,size_t);
ssize_t md_provider_tree_getdents(struct md_provider_tree *,int fd,void *,size_t);
ssize_t md_provider_tree_host_getdents(struct md_provider_tree *,int fd,void *,size_t);
int md_provider_tree_control(struct md_provider_tree *,int fd,struct md_control_file *);
int md_provider_tree_private(struct md_provider_tree *,int fd);
int md_provider_tree_readonly(struct md_provider_tree *,int fd);
unsigned long md_provider_tree_filesystem(struct md_provider_tree *);
/* Borrowed owner state, only for the matching typed provider. */
void *md_provider_tree_context(struct md_provider_tree *,const struct md_provider_tree_ops *);
#endif
