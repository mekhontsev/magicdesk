#define _GNU_SOURCE
#include "file_open.h"
#include <errno.h>
#include <limits.h>
#include <linux/futex.h>
#include <poll.h>
#include <stdint.h>
#include <sys/file.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>
#if __STDC_HOSTED__
static long call(long nr, long a, long b, long c, long d, long e, long f) {
    long r = syscall(nr, a, b, c, d, e, f);
    return r == -1 ? -errno : r;
}
#else
#include "raw.h"
#define call md_raw
#endif
#define C(n,a,b,c,d,e,f) call(SYS_##n,(long)(a),(long)(b),(long)(c),(long)(d),(long)(e),(long)(f))

static void descriptor_path(char path[64], int fd) {
    const char prefix[] = "/proc/self/fd/";
    unsigned at = 0, n = (unsigned)fd, digits = 0;
    char reversed[16];
    while (prefix[at]) { path[at] = prefix[at]; at++; }
    do { reversed[digits++] = (char)('0' + n % 10); n /= 10; } while (n);
    while (digits) path[at++] = reversed[--digits];
    path[at] = 0;
}
int md_complete_open(int pin, int flags, struct md_open_completion completion) {
    if (pin < 0 || completion.kind == MD_OPEN_READY) return pin;
    char path[64]; descriptor_path(path, pin);
    flags = md_open_endpoint_flags(flags);
    if (completion.kind == MD_OPEN_FIFO) {
        /* EVENT_WAIT: native FIFO peer rendezvous; a caller signal cancels it.
         * No namespace worker or transaction is retained. */
        long r = C(openat, AT_FDCWD, path, flags, 0, 0, 0);
        C(close, pin, 0, 0, 0, 0, 0);
        return (int)r;
    }
    int control = completion.control, fd = -1, locked = 0;
    uint32_t *generations = NULL;
    long mapping = C(mmap, 0, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, control, 0);
    long r = mapping < 0 && mapping >= -4095 ? mapping : 0;
    if (!r) generations = (void *)mapping;
    if (!r) { r = C(flock, control, LOCK_EX, 0, 0, 0, 0); locked = !r; }
    unsigned mode = flags & O_ACCMODE;
    unsigned peer = mode == O_RDONLY ? 1 : 0;
    uint32_t before = r ? 0 : __atomic_load_n(&generations[peer], __ATOMIC_ACQUIRE);
    if (!r) {
        r = C(openat, AT_FDCWD, path, flags, 0, 0, 0);
        if (r >= 0) { fd = (int)r; r = 0; }
    }
    struct pollfd state = {.fd = fd, .events = POLLIN | POLLOUT};
    struct timespec zero = {0};
    if (!r && mode != O_RDWR) {
        long ready = C(ppoll, &state, 1, &zero, 0, 0, 0);
        if (ready < 0) r = ready;
    }
    int absent = mode == O_RDONLY ? !!(state.revents & POLLHUP) : !!(state.revents & POLLERR);
    if (!r && mode == O_WRONLY && absent && (flags & O_NONBLOCK)) r = -ENXIO;
    if (!r) {
        for (unsigned side = 0; side < 2; side++) {
            if ((side == 0 && mode != O_WRONLY) || (side == 1 && mode != O_RDONLY)) {
                __atomic_store_n(&generations[side], __atomic_load_n(&generations[side], __ATOMIC_RELAXED) + 1,
                    __ATOMIC_RELEASE);
                C(futex, &generations[side], FUTEX_WAKE, INT_MAX, 0, 0, 0);
            }
        }
    }
    if (locked) C(flock, control, LOCK_UN, 0, 0, 0, 0);
    /* EVENT_WAIT: another opener publishes its endpoint generation. Signals or
     * process cancellation terminate the wait; no SQLite/store lock is held. */
    while (!r && mode != O_RDWR && absent && !(flags & O_NONBLOCK)
            && __atomic_load_n(&generations[peer], __ATOMIC_ACQUIRE) == before) {
        r = C(futex, &generations[peer], FUTEX_WAIT, before, 0, 0, 0);
        if (r == -EAGAIN) r = 0;
    }
    if (generations) C(munmap, generations, 4096, 0, 0, 0, 0);
    C(close, control, 0, 0, 0, 0, 0);
    C(close, pin, 0, 0, 0, 0, 0);
    if (r && fd >= 0) C(close, fd, 0, 0, 0, 0, 0);
    return r ? (int)r : fd;
}
