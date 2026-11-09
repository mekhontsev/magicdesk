#ifndef MD_CGROUP_H
#define MD_CGROUP_H
#include "control_file.h"
#include "pid_namespace.h"
#include "fs_anchor.h"
struct md_cgroup_member;
struct md_cgroup_view;
struct md_cgroup_namespace;
struct md_cgroup_programs;
struct md_cgroup_program;
struct md_inode_store;
/* EOPNOTSUPP selects native watches for controls without typed events. */
int md_cgroup_watch(struct md_cgroup_view *,struct md_inode_store *,int,int,unsigned);
struct md_cgroup_programs *md_cgroup_program_registry(struct md_cgroup_member *,int create);
int md_cgroup_program_query(struct md_cgroup_view *,int,struct md_cgroup_member *,
    const struct md_identity *,unsigned,uint32_t *,unsigned,unsigned *,unsigned *);
int md_cgroup_program_attach(struct md_cgroup_view *,int,struct md_cgroup_member *,
    const struct md_identity *,struct md_cgroup_program *,unsigned,int detach);
struct md_cgroup_namespace *md_cgroup_namespace_new(struct md_cgroup_member *,struct md_user_namespace *);
struct md_cgroup_namespace *md_cgroup_namespace_retain(struct md_cgroup_namespace *);
void md_cgroup_namespace_release(struct md_cgroup_namespace *);
uint64_t md_cgroup_namespace_inode(const struct md_cgroup_namespace *);
const struct md_user_namespace *md_cgroup_namespace_owner(const struct md_cgroup_namespace *);
struct md_cgroup_member *md_cgroup_member_new(void);
struct md_cgroup_member *md_cgroup_member_fork(struct md_cgroup_member *);
/* Reserve a destination before native birth, without visible membership/events.
 * Publication commits it; releasing the unpublished member rolls it back. */
int md_cgroup_member_clone(struct md_cgroup_member *,struct md_fs_anchor *,
    const struct md_identity *,const struct md_cgroup_namespace *,int thread,
    struct md_cgroup_member **);
struct md_cgroup_member *md_cgroup_member_retain(struct md_cgroup_member *);
void md_cgroup_member_release(struct md_cgroup_member *);
/* Launch-wide sampled task CPU, in native clock ticks; independent of mounts. */
int md_cgroup_cpu_usage(struct md_cgroup_member *,uint64_t *user,uint64_t *system);
void md_cgroup_member_publish(struct md_cgroup_member *,pid_t,pid_t,struct md_pid_identity *,struct md_pid_identity *);
void md_cgroup_member_depart(struct md_cgroup_member *,pid_t);
/* Sample only observed accounting, at the existing terminal ptrace stop. */
void md_cgroup_member_account_exit(struct md_cgroup_member *);
/* Drop quota custody at native reaping, independently of retained proc FDs. */
void md_cgroup_member_reaped(struct md_cgroup_member *);
/* Requested state is independent from the supervisor's confirmed suspension.
 * Generation changes wake the existing resource loop, never a periodic scan. */
uint64_t md_cgroup_freezer_generation(const struct md_cgroup_member *);
int md_cgroup_member_freezing(const struct md_cgroup_member *);
void md_cgroup_member_frozen(struct md_cgroup_member *,int);
/* A unified hierarchy, a permissive v1 devices hierarchy and named v1
 * membership hierarchies per launch. Mounts, namespaces and open controls
 * retain them. Resource policy is not enforced against Android cgroups. */
struct md_cgroup_view *md_cgroup_mount(struct md_cgroup_member *,int parent,int legacy,
    const struct md_identity *,const struct md_cgroup_namespace *,const char *options);
int md_cgroup_parse_options(const char *,unsigned *);
int md_cgroup_options(struct md_cgroup_view *,char *,size_t);
struct md_cgroup_view *md_cgroup_retain(struct md_cgroup_view *);
void md_cgroup_release(struct md_cgroup_view *);
/* Returns an owned O_PATH root, selected under the hierarchy lock. */
int md_cgroup_open_root(struct md_cgroup_view *,const struct md_cgroup_namespace *);
int md_cgroup_legacy(const struct md_cgroup_view *);
unsigned long md_cgroup_filesystem(struct md_cgroup_view *);
int md_cgroup_stat(struct md_cgroup_view *,int,struct stat *);
int md_cgroup_chmod(struct md_cgroup_view *,int,mode_t,const struct md_identity *);
int md_cgroup_chown(struct md_cgroup_view *,int,uint32_t,uint32_t,const struct md_identity *);
int md_cgroup_mkdir(struct md_cgroup_view *,int,const char *,const struct md_identity *);
int md_cgroup_rmdir(struct md_cgroup_view *,int,const char *,const struct md_identity *);
int md_cgroup_open(struct md_cgroup_view *,int,int,const struct md_identity *,struct md_cgroup_namespace *,struct md_fs_anchor *,
    const struct md_control_file_ops **,void **);
long md_cgroup_member_text(struct md_cgroup_member *,const struct md_cgroup_namespace *,char *,size_t);
long md_cgroup_controllers_text(struct md_cgroup_member *,char *,size_t);
#endif
