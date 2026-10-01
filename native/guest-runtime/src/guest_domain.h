#ifndef MD_GUEST_DOMAIN_H
#define MD_GUEST_DOMAIN_H
#include <sys/types.h>

/* Kernel-only adapter transport in every domain. This shared list must not
 * include pathname, credential or argument-dependent policy operations. */
#define MD_GATE_TRANSPORT_CALLS(X) X(sendmsg) X(recvmsg)

/* No adaptation, observation or logical identity in any domain. Shared with
 * seccomp so these kernel operations need no userspace policy round trip. */
#define MD_DOMAIN_KERNEL_CALLS(X) \
    X(fstatfs) X(pipe2) X(shutdown) X(socketpair) X(wait4) X(waitid) \
    X(capget) X(capset) X(uname) X(sysinfo) X(sched_yield) X(sched_getaffinity) \
    X(getrusage) X(getrlimit) X(gettimeofday)
#define MD_DOMAIN_KERNEL_ARGUMENTS(X) \
    X(fcntl, 1, F_GETFD) X(fcntl, 1, F_SETFD) X(fcntl, 1, F_GETFL) \
    X(fcntl, 1, F_SETFL) X(fcntl, 1, F_DUPFD) X(fcntl, 1, F_DUPFD_CLOEXEC) \
    X(prlimit64, 0, 0) \
    X(prctl, 0, PR_GET_DUMPABLE) X(prctl, 0, PR_SET_NAME) \
    X(prctl, 0, PR_GET_NAME) X(prctl, 0, PR_GET_SECCOMP)

/* Shared, irrevocably restricted proc-root views. This is
 * not a general guest chroot or a credential implementation. */
struct md_guest_domain;
struct md_guest_domain *md_domain_new(void);
struct md_guest_domain *md_domain_fork(struct md_guest_domain *, int shared);
void md_domain_release(struct md_guest_domain *);
int md_domain_restricted(const struct md_guest_domain *);
int md_domain_restrict(struct md_guest_domain *, pid_t, const char *);
int md_domain_chdir_root(struct md_guest_domain *);
int md_domain_path_error(const struct md_guest_domain *);
int md_domain_native_call(long nr, const unsigned long args[6]);
/* Returns an owned descriptor or a negative errno. No pathname continuation in
 * the tracee: the copied request is resolved beneath the retained resource. */
int md_domain_open_retained(int directory, pid_t caller, const char *path, int flags);
int md_domain_proc_fd_query(int directory, const char *path, int flags);
int md_domain_proc_fd_number(int directory, pid_t caller, const char *path);
#endif
