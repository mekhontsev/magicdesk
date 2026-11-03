#ifndef MD_PID_NAMESPACE_H
#define MD_PID_NAMESPACE_H
#include "user_namespace.h"
#include <stddef.h>
#include <sys/types.h>

struct md_pid_namespace;
struct md_pid_identity;
struct md_identity;
enum { MD_PID_LEVELS=32, MD_PID_MAX=4194304 };
struct md_pid_number_value { uint64_t namespace; int32_t number; uint32_t reserved; };
/* Immutable connection/message-time numbers, independent of later reaping or
 * native PID reuse. No process reference is needed after capture. */
unsigned md_pid_identity_export(const struct md_pid_identity *, struct md_pid_number_value *);
int md_pid_numbers_visible(const void *, size_t, const struct md_pid_namespace *, pid_t native);
/* NULL is the executor's native PID namespace. Child namespaces and their
 * identities are launch-owned; references never confer host signal authority. */
struct md_pid_namespace *md_pid_namespace_new(struct md_pid_namespace *, struct md_user_namespace *);
struct md_pid_namespace *md_pid_namespace_retain(struct md_pid_namespace *);
void md_pid_namespace_release(struct md_pid_namespace *);
struct md_pid_namespace *md_pid_namespace_parent(const struct md_pid_namespace *);
const struct md_user_namespace *md_pid_namespace_owner(const struct md_pid_namespace *);
uint64_t md_pid_namespace_inode(const struct md_pid_namespace *);
unsigned md_pid_namespace_depth(const struct md_pid_namespace *);
int md_pid_namespace_contains(const struct md_pid_namespace *, const struct md_pid_namespace *);
int md_pid_namespace_dead(const struct md_pid_namespace *);
int md_pid_namespace_initialized(struct md_pid_namespace *);
/* Admission is read-only and returns the parent's wait signal. Publish it
 * only after the process owner successfully requests PID 1 termination. */
int md_pid_namespace_reboot(struct md_pid_namespace *,const struct md_identity *,unsigned,unsigned,unsigned);
int md_pid_identity_reboot_signal(const struct md_pid_identity *);
void md_pid_namespace_reboot_started(struct md_pid_namespace *,int signal);
/* Local owner lookup for a serialized mount reference. A foreign/dead owner
 * is not replaced by the executor's namespace. */
struct md_pid_namespace *md_pid_namespace_by_inode(uint64_t);

/* Reserve before clone. Publication at the kernel birth event allocates
 * nothing. Failed clones release the unpublished reservation. */
int md_pid_identity_prepare(struct md_pid_namespace *, struct md_pid_identity **);
/* Requested numbers run from the innermost logical namespace outwards.
 * The native ancestor, if explicitly selected, remains kernel-owned. */
int md_pid_identity_prepare_selected(struct md_pid_namespace *,const struct md_identity *,
    const pid_t *,unsigned,struct md_pid_identity **);
int md_pid_identity_publish(struct md_pid_identity *, pid_t native, pid_t group);
struct md_pid_identity *md_pid_identity_retain(struct md_pid_identity *);
void md_pid_identity_release(struct md_pid_identity *);
struct md_pid_namespace *md_pid_identity_namespace(const struct md_pid_identity *);
pid_t md_pid_identity_native(const struct md_pid_identity *);
pid_t md_pid_identity_group(const struct md_pid_identity *);
pid_t md_pid_identity_number(const struct md_pid_identity *, const struct md_pid_namespace *);
int md_pid_identity_live(const struct md_pid_identity *);
int md_pid_identity_subreaper(const struct md_pid_identity *);
void md_pid_identity_set_subreaper(struct md_pid_identity *, int);
/* Retires the task. The last live thread in PID 1 closes namespace admission;
 * the process owner then terminates descendants. */
int md_pid_identity_exit(struct md_pid_identity *);
/* Reaping removes lookup visibility, independently of retained pidfds. */
void md_pid_identity_reap(struct md_pid_identity *);
/* Nonleader exec retains the process number while retiring its former TID. */
void md_pid_identity_exec(struct md_pid_identity *thread, struct md_pid_identity *group);
/* A retained result remains valid through exit/reaping; callers separately
 * check liveness before issuing a host operation against the native number. */
struct md_pid_identity *md_pid_namespace_lookup(struct md_pid_namespace *, pid_t visible);
struct md_pid_identity *md_pid_namespace_find(struct md_pid_namespace *, pid_t native);
#endif
