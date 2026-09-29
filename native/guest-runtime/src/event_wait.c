#define _GNU_SOURCE
#include "event_wait.h"
#include "raw.h"
#include <errno.h>
#include <time.h>

long md_event_now(void) {
    struct timespec time;
    long r = RAW2(clock_gettime, CLOCK_MONOTONIC, &time);
    return r < 0 ? r : time.tv_sec * 1000000000LL + time.tv_nsec;
}
long md_event_wait(struct pollfd *fds, unsigned count, int64_t deadline) {
    for (;;) {
        struct timespec timeout, *bound = NULL;
        if (deadline != INT64_MAX) {
            long now = md_event_now();
            if (now < 0) return now;
            int64_t remaining = deadline - now;
            if (remaining <= 0) return -ETIMEDOUT;
            timeout = (struct timespec){remaining / 1000000000LL, remaining % 1000000000LL};
            bound = &timeout;
        }
        /* EVENT_WAIT: descriptor events; an absolute deadline is failure, never readiness. */
        long r = RAW5(ppoll, fds, count, bound, 0, 8);
        if (r == -EINTR) continue;
        if (r < 0) return r;
        if (!r) return -ETIMEDOUT;
        for (unsigned i = 0; i < count; ++i)
            if (fds[i].revents & POLLNVAL) return -EBADF;
        return 0;
    }
}
long md_event_wait_fd(int fd, short events, int64_t deadline) {
    struct pollfd item = {.fd = fd, .events = events};
    return md_event_wait(&item, 1, deadline);
}
