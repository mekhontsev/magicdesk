#ifndef MD_FD_CLIENT_H
#define MD_FD_CLIENT_H
#include "fd_selector.h"
enum md_fd_control { MD_FD_INPUT=1, MD_FD_SENT, MD_FD_RECEIVE, MD_FD_RECEIVED, MD_FD_END,
    MD_FD_USE, MD_FD_RELEASE, MD_FD_PAIR_BEGIN, MD_FD_PAIR_END,
    MD_FD_SELECTOR_QUERY, MD_FD_SELECTOR_INSTALL, MD_FD_KIND, MD_FD_SELECTOR_EXIT,
    MD_FD_SELECTORS, MD_FD_DUPLICATE_BEGIN, MD_FD_DUPLICATE_END };
/* These C replies never inject a task call or restore application registers. */
static inline int md_fd_control_scalar(unsigned long command) {
    return command==MD_FD_KIND || command==MD_FD_SELECTORS
        || command==MD_FD_SELECTOR_QUERY || command==MD_FD_SENT
        || command==MD_FD_RECEIVED || command==MD_FD_END
        || command==MD_FD_RELEASE || command==MD_FD_PAIR_END;
}
/* USE/RELEASE bracket short task-affine native descriptor operations only.
 * They allocate no exchange and must not cover blocking reads or rendezvous. */
#ifdef MD_USE_LIBC
static inline struct md_fd_observation md_fd_observe(int fd) {
    (void)fd; return (struct md_fd_observation){0};
}
static inline long md_fd_control(unsigned op, uint64_t id, long first, long second) {
    (void)op; (void)id; (void)first; (void)second; return 0;
}
#else
struct md_fd_observation md_fd_observe(int fd);
long md_fd_control(unsigned op, uint64_t id, long first, long second);
void md_fd_client_enable(void);
#endif
#endif
