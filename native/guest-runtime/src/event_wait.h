#ifndef MD_EVENT_WAIT_H
#define MD_EVENT_WAIT_H
#include <poll.h>
#include <stdint.h>

long md_event_now(void);
/* Absolute monotonic nanoseconds; INT64_MAX means no time limit. */
long md_event_wait(struct pollfd *, unsigned count, int64_t deadline);
long md_event_wait_fd(int fd, short events, int64_t deadline);
#endif
