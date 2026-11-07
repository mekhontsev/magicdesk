#ifndef MD_SIGNAL_FD_H
#define MD_SIGNAL_FD_H
#include "fd_object.h"
#include <signal.h>
#include <sys/signalfd.h>

/* The real signalfd owns its kernel mask, flags and lifetime. This annotation
 * retains only the corresponding logical selection; it never binds a reader.
 * Mask updates and reads are coordinated by the supervisor's FD turn. */
struct md_signal_fd;
struct md_signal_fd *md_signal_fd_new(void);
struct md_signal_fd *md_signal_fd_from_object(struct md_fd_object *);
struct md_fd_object *md_signal_fd_object(struct md_signal_fd *);
uint64_t md_signal_fd_mask(const struct md_signal_fd *);
void md_signal_fd_update(struct md_signal_fd *,uint64_t);
void md_signal_fd_info(const siginfo_t *,struct signalfd_siginfo *);
#endif
