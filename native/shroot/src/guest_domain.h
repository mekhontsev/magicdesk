#ifndef MD_GUEST_DOMAIN_H
#define MD_GUEST_DOMAIN_H
#include <sys/types.h>
#include "fs_location.h"
#include "fs_anchor.h"
#include "resource_order.h"

/* Kernel-only adapter transport in every domain. This shared list must not
 * include pathname, credential or argument-dependent policy operations. */
#define MD_GATE_TRANSPORT_CALLS(X) X(sendmsg) X(recvmsg) X(uname)

/* Kernel descriptor operations with ordered provenance publication. Their
 * observation does not grant pathname access or additional kernel authority. */
#define MD_DOMAIN_FD_CALLS(X) X(close) X(dup) X(dup3) X(close_range) X(epoll_create1)
#define MD_DOMAIN_FD_ARGUMENTS(X) \
    X(fcntl, 1, F_SETFD) X(fcntl, 1, F_DUPFD) X(fcntl, 1, F_DUPFD_CLOEXEC)

/* No adaptation, observation or logical identity in any domain. Shared with
 * seccomp so these kernel operations need no userspace policy round trip. */
#define MD_DOMAIN_WAIT_CALLS(X) X(ppoll) X(pselect6) X(epoll_ctl)
/* Execution state belongs to the kernel. These calls precede address-based
 * service gates in the base filter; PID/FD selectors and guest filters still
 * apply. gettid is deliberately absent: it also carries service checkpoints. */
#define MD_DOMAIN_EXECUTION_CALLS(X) \
    X(futex) X(set_robust_list) X(get_robust_list) X(madvise) X(brk) \
    X(rt_sigaction) X(rt_sigprocmask) X(rt_sigreturn) X(sigaltstack) \
    X(getrandom) X(clock_gettime) X(clock_nanosleep) X(nanosleep)
#define MD_DOMAIN_KERNEL_CONTROL_CALLS(X) \
    X(shutdown) X(waitid) \
    X(sysinfo) X(sched_yield) X(sched_getaffinity) \
    X(getrusage) X(getrlimit) X(gettimeofday)
#define MD_DOMAIN_KERNEL_CALLS(X) \
    MD_DOMAIN_WAIT_CALLS(X) MD_DOMAIN_EXECUTION_CALLS(X) MD_DOMAIN_KERNEL_CONTROL_CALLS(X)
#define MD_DOMAIN_KERNEL_ARGUMENTS(X) \
    X(fcntl, 1, F_GETFD) X(fcntl, 1, F_GETFL) X(fcntl, 1, F_SETFL) \
    X(prlimit64, 0, 0) \
    X(prctl, 0, PR_GET_DUMPABLE) X(prctl, 0, PR_SET_NAME) \
    X(prctl, 0, PR_GET_NAME) X(prctl, 0, PR_GET_SECCOMP)

/* CLONE_FS-owned root context. Registry pins do not count as task owners. */
struct md_guest_domain;
struct md_mount_namespace;
/* Bind only a private, unpublished context. Fork inherits this membership;
 * namespace-copy preparation changes it before making the candidate live. */
void md_domain_bind_mounts(struct md_guest_domain *, struct md_mount_namespace *);
int md_domain_mount_root_used(struct md_mount_namespace *, unsigned mount);
void md_domain_relocate_roots(struct md_mount_namespace *, const struct md_fs_location *old,
        const struct md_fs_location *next, const struct md_fs_anchor *old_cwd,
        struct md_fs_anchor *next_cwd);
/* Independent of the CLONE_FILES queue. Operations needing both resources
 * always acquire files first, then filesystem context, and release in reverse. */
struct md_fs_order {
    struct md_guest_domain *fs;
    struct md_resource_order order;
};
int md_fs_order_begin(struct md_guest_domain *, struct md_fs_order *, struct md_resource_wakeup *);
enum md_resource_order_state md_fs_order_state(const struct md_fs_order *);
int md_fs_order_start(struct md_fs_order *);
int md_fs_order_cancel(struct md_fs_order *);
int md_fs_order_complete(struct md_fs_order *);
struct md_guest_domain *md_domain_new(void);
struct md_guest_domain *md_domain_fork(struct md_guest_domain *, int shared);
struct md_guest_domain *md_domain_unshare_prepare(struct md_guest_domain *);
/* Supervisor-owned task membership, excluding registry and request pins. */
int md_domain_shared(const struct md_guest_domain *);
void md_domain_release(struct md_guest_domain *);
void md_domain_pin(struct md_guest_domain *);
void md_domain_unpin(struct md_guest_domain *);
int md_domain_scoped(const struct md_guest_domain *);
/* Snapshot retains cwd provenance; release it separately from this domain. */
void md_domain_paths(struct md_guest_domain *, struct md_fs_location *, struct md_fs_anchor **cwd);
int md_domain_set_cwd(struct md_guest_domain *, struct md_fs_anchor *);
int md_domain_set_root(struct md_guest_domain *, const struct md_fs_location *);
void md_domain_relocate_root(struct md_guest_domain *, const struct md_fs_location *old,
        const struct md_fs_location *next, const struct md_fs_anchor *old_cwd,
        struct md_fs_anchor *next_cwd);
int md_domain_restricted(const struct md_guest_domain *);
const char *md_domain_proc_root_name(const char *);
int md_domain_restrict(struct md_guest_domain *, pid_t, const char *);
int md_domain_chdir_root(struct md_guest_domain *);
int md_domain_path_error(const struct md_guest_domain *);
/* Unsupported guest interfaces fail before entering an unrelated host service. */
int md_domain_socket_error(int family, int protocol);
int md_domain_native_call(long nr, const unsigned long args[6]);
/* Application ancillary calls retain custody under a restricted filesystem. */
int md_domain_ipc_call(long nr);
/* Returns an owned descriptor or a negative errno. No pathname continuation in
 * the tracee: the copied request is resolved beneath the retained resource. */
int md_domain_open_retained(int directory, pid_t caller, const char *path, int flags);
int md_domain_proc_fd_query(int directory, const char *path, int flags);
int md_domain_proc_fd_number(int directory, pid_t caller, const char *path);
#endif
