#ifndef MD_EPOLL_WAIT_H
#define MD_EPOLL_WAIT_H
#include "control_epoll.h"
#include "epoll_calls.h"
#include "ipc_io_job.h"
#include "task_call.h"
#include <poll.h>

/* Operation-scoped borrow of the native queue. No hidden registration is
 * inserted into it, and no native OFD is retained by the virtual authority. */
struct md_epoll_wait {
    struct md_ipc_io_job job;
    struct pollfd sources[2];
    int64_t deadline;
    int initialized;
    int task_started, virtual_count, blocking;
};
void md_epoll_wait_initialize(struct md_epoll_wait *,const struct md_epoll_frame *);
void md_epoll_wait_cancel(struct md_epoll_wait *);
/* Start a virtual wake observer only once the queue has a transport. The
 * caller repeats this at kernel entry to cover concurrent cold admission. */
int md_epoll_wait_observe(struct md_epoll_wait *,struct md_control_epoll *);
/* Prepare/consume a caller-context native wait. The owner retains the exact
 * caller slot through the operation; completed native output is in frame. */
int md_epoll_wait_task(struct md_epoll_wait *,struct md_control_epoll *,
    struct md_epoll_frame *,uintptr_t address,long number,int descriptor,
    int completed,long native_result,struct md_task_call_arguments *,long *result);
#endif
