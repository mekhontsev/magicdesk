#ifndef MD_EPOLL_CALLS_H
#define MD_EPOLL_CALLS_H
#include "fd_capture.h"
#include "task_call.h"
#include <sys/epoll.h>
#include <time.h>
#include <poll.h>

enum { MD_EPOLL_EVENTS=256, MD_EPOLL_NATIVE=0x50000001, MD_EPOLL_CAPTURED=0x50000002,
    MD_EPOLL_LOAN=0x50000003, MD_EPOLL_PARK=0x50000004,
    MD_EPOLL_COPY=0x50000005, MD_EPOLL_DONE=0x50000006 };
struct md_epoll_park { struct pollfd queues[2]; struct timespec timeout; uint64_t mask; };
struct md_epoll_frame {
    struct md_fd_capture_frame capture;
    struct md_task_call_arguments admission;
    struct epoll_event event;
    struct timespec timeout;
    uint64_t mask;
    uintptr_t output;
    long result;
    int capacity, timed, masked, native_output;
    int copy_count, copy_offset, copy_read;
    struct epoll_event events[MD_EPOLL_EVENTS];
    struct epoll_event task_events[MD_EPOLL_EVENTS];
    struct md_epoll_park park;
};
#ifndef MD_USE_LIBC
long md_epoll_call(long,const unsigned long *);
#endif
#endif
