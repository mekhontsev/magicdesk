#ifndef MD_PROCESS_OWNER_H
#define MD_PROCESS_OWNER_H
#include <stdint.h>

struct md_kernel_action { uintptr_t handler, flags, restorer; uint64_t mask; };
struct md_process_signals {
    uint64_t previous_mask;
    struct md_kernel_action previous[5];
    int fd;
};
long md_process_signals_open(struct md_process_signals *);
long md_process_signals_restore(const struct md_process_signals *);
/* Drain pending supervisor signals; return the first cancellation signal or zero. */
long md_process_signals_read(int fd);
long md_process_signal(int pidfd, int signal);
long md_process_signal_children(int signal);
long md_process_close_fds(const int *keep, unsigned count);
int md_process_status(int status);
long md_process_guard(long owner, const char *bootstrap,
                      char **argv, char **env, const struct md_process_signals *inherited);
#endif
