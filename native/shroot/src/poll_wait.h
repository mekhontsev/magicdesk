#ifndef MD_POLL_WAIT_H
#define MD_POLL_WAIT_H
#include "poll_calls.h"
#include "fd_origins.h"
#include "ipc_io_job.h"
#include "task_call.h"
struct md_poll_wait;
struct md_epoll_wait;
struct md_poll_wait *md_poll_wait_new(unsigned count,int select);
void md_poll_wait_free(struct md_poll_wait *);
/* Input chunks are captured under the caller's file-table turn. */
int md_poll_wait_load(struct md_poll_wait *,struct md_fd_origins *,const struct md_poll_frame *);
int md_poll_wait_virtual(const struct md_poll_wait *);
int md_poll_wait_copy(const struct md_poll_wait *,struct md_poll_frame *,unsigned offset);
/* Collect only providers when the caller's native wait was interrupted. */
int md_poll_wait_interrupted(struct md_poll_wait *,const struct md_control_actor *);
int md_poll_wait_task_ready(const struct md_poll_wait *,const struct md_control_actor *);
/* Native polling stays task-affine, including signalfd and temporary masks.
 * The helper waits only on provider-owned wake descriptors. */
int md_poll_wait_task(struct md_poll_wait *,struct md_epoll_wait *,struct md_poll_frame *,
    uintptr_t address,int completed,long value,const struct md_control_actor *,struct md_task_call_arguments *,long *result);
#endif
