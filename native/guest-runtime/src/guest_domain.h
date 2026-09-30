#ifndef MD_GUEST_DOMAIN_H
#define MD_GUEST_DOMAIN_H
#include <sys/types.h>

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
