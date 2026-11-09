#ifndef MD_TASK_FILTER_H
#define MD_TASK_FILTER_H
#include "fd_filter_program.h"
#include "task_call.h"

/* Selector policy over the shared task-affine syscall executor. The owner
 * retains the mapped image, actor and original event until completion. */
struct md_task_filter {
    struct md_task_call task;
    uintptr_t program;
    unsigned thread_only;
};
int md_task_filter_begin(struct md_task_filter *,long (*trace)(int,pid_t,void *,void *),
    pid_t,enum md_task_call_boundary,const struct user_pt_regs *resume,
    uintptr_t call,uintptr_t returned,struct md_task_filter_image *,uintptr_t image_address,
    enum md_fd_selector_kind,int fd,const struct md_fd_filter_gates *);
/* Install an already-built, owner-controlled program through the same executor. */
int md_task_filter_begin_program(struct md_task_filter *,long (*trace)(int,pid_t,void *,void *),
    pid_t,enum md_task_call_boundary,const struct user_pt_regs *resume,
    uintptr_t call,uintptr_t returned,struct md_task_filter_image *,uintptr_t image_address);
int md_task_filter_event(struct md_task_filter *,int status);
/* Retry a completed EPERM via PR_SET_SECCOMP. The owner must prove that the
 * actor is the group's only kernel task and exclude births for this call.
 * Per-thread success never substitutes for an unsynchronized group. */
int md_task_filter_retry_thread(struct md_task_filter *);
#endif
