#ifndef MD_SUPERVISOR_FS_H
#define MD_SUPERVISOR_FS_H

#include "supervisor_thread.h"
#include "fs_continuation.h"
#include "task_helper.h"

struct capture_owner {
    pid_t caller;
    struct md_fs_buffer_request *request;
    struct md_fs_task_capture target;
    struct md_fs_task_subject subject;
    struct md_task_helper_result result;
    int (*authorize)(struct thread *,struct thread *);
    void (*complete)(struct thread *,struct md_task_helper_result);
    int metadata, started, foreign;
    size_t directory_limit;
};

int buffer_release(struct thread *t);
void adapter_discard(struct thread *t);
int duplicate_fd(pid_t pid, int descriptor);
int read_string(pid_t pid, uintptr_t address, char *text, size_t capacity);
void export_begin(struct thread *t, const struct seccomp_notif *q);
void export_start(struct thread *t,const struct user_pt_regs *observed);
void replay(struct thread *t);
void export_progress(struct thread *t);
void export_control(struct thread *t, const struct user_pt_regs *regs);
void store_next(struct thread *t);
void retained_result(struct thread *t, const struct seccomp_notif *q);
void delegate_begin(struct thread *t, const struct seccomp_notif *q);
void buffer_setup(struct thread *t, const struct seccomp_notif *q);
void external_stat(void);
void buffer_reply_signal(struct thread *t, long result, const siginfo_t *signal);
void buffer_reply(struct thread *t, long result);
void capture_complete(void *context, struct md_task_helper_result result);
int direct_addfd(const struct thread *t, const struct md_fs_buffer_frame *frame);
void buffer_complete(struct md_fs_buffer_request *request, pid_t pid, int error);
int buffer_cleanup(struct thread *t);
long buffer_result_finish(struct thread *t,long copied,long result);
void buffer_take(struct thread *t);
void buffer_return_progress(struct thread *t);
void fs_buffer_control(struct thread *t,const struct supervisor_control_request *control);
int direct_begin(struct thread *t);
void file_wait_complete(struct thread *t);
int fs_result_exit(struct thread *t,pid_t pid);
int fs_direct_exit(struct thread *t,pid_t pid);
int fs_wait_exit(struct thread *t,pid_t pid);
int fs_export_exit(struct thread *t,pid_t pid);
int fs_checkpoint(struct thread *t,uintptr_t checkpoint);

#endif
