#ifndef MD_PROFILE_H
#define MD_PROFILE_H
#include <errno.h>
#include <stdint.h>
#include <time.h>

struct md_cost { uint64_t calls, nanoseconds; };
/* Optional diagnostic elapsed time, including descheduling and kernel waits.
 * Never sample the clock on an ordinary launch or disturb the operation's errno. */
static inline int64_t md_cost_begin(const struct md_cost *cost) {
    if (!cost) return -1;
    int saved = errno;
    struct timespec ts;
    int result = clock_gettime(CLOCK_MONOTONIC, &ts);
    errno = saved;
    return result ? -1 : (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}
static inline void md_cost_end(struct md_cost *cost, int64_t begin) {
    if (!cost) return;
    int64_t end = md_cost_begin(cost);
    cost->calls++;
    if (begin >= 0 && end >= begin) cost->nanoseconds += (uint64_t)(end - begin);
}
#endif
