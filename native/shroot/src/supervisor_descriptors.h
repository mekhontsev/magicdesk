#ifndef MD_SUPERVISOR_DESCRIPTORS_H
#define MD_SUPERVISOR_DESCRIPTORS_H

#include "supervisor_thread.h"

void fd_discard(struct thread *t);
void fd_return(struct thread *t,struct user_pt_regs *regs,long result);
void fd_finish(struct thread *t, long result);
void mount_copy_complete(struct md_mount_copy_request *request, int actor, int error);
void fd_start(struct thread *t);
void fd_begin(struct thread *t,const struct seccomp_data *call,const struct user_pt_regs *r,unsigned operation);
int fd_close_compact(struct thread *t,unsigned long cookie,const struct ptrace_syscall_info *info);
int fd_duplicate_prepared(struct thread *t,const struct seccomp_data *call,unsigned long cookie);
int fd_native_io(struct thread *t,const struct ptrace_syscall_info *info);
/* 0 needs its adapter, 1 native, 2 native epoll probe before owned waiting. */
int fd_native_classify(struct thread *,const struct seccomp_data *,unsigned cookie);
void fd_control(struct thread *t,const struct supervisor_control_request *control,struct user_pt_regs *r);
void close_detach(struct thread *t);
void close_progress(struct thread *t);
void close_event_begin(struct thread *t,unsigned event,unsigned long message);
void close_wait_complete(struct thread *t);
void control_input_discard(struct thread *t);
void buffer_descriptor_start(struct thread *t);
void buffer_descriptor_begin(struct thread *t,const struct supervisor_control_request *control);
void descriptors_buffer_control(struct thread *t,const struct supervisor_control_request *control);
int network_descriptor_call(long nr);
int descriptor_call(struct thread *t,const struct seccomp_data *call,struct user_pt_regs *r, unsigned long cookie);
int fd_capture_valid(const struct thread *t,const struct seccomp_data *call);
int descriptors_native_exit(struct thread *t,pid_t pid);
int descriptors_close_exit(struct thread *t,pid_t pid);

#endif
