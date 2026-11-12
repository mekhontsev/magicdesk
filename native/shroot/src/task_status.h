#ifndef MD_TASK_STATUS_H
#define MD_TASK_STATUS_H
#include <stdint.h>
#include <sys/types.h>
#include <sys/resource.h>
/* Kernel state read by its native owner, never cached or changed to inspect it. */
long md_task_status_open(int tid);
/* The caller owns the descriptor; each read starts a fresh proc snapshot. */
long md_task_umask_fd(int fd);
long md_task_umask(int tid);
long md_task_signal_dispositions(int tid, uint64_t *caught, uint64_t *ignored);
long md_task_parent(int tid);
int md_task_child_class(pid_t,pid_t *group,int *exit_signal);
/* Cross-task prlimit may be denied by Android while its proc snapshot is readable. */
long md_task_limit(int tid,unsigned resource,struct rlimit *value);
long md_task_limit_fd(int fd,unsigned resource,struct rlimit *value);
#endif
