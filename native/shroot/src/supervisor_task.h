#ifndef MD_SUPERVISOR_TASK_H
#define MD_SUPERVISOR_TASK_H

#include "supervisor_thread.h"

enum { THREAD_BUCKETS = 257 };

extern struct thread *threads;
extern int pid_collection_pending;
extern struct md_cgroup_member *freezer;
extern int freezer_enabled;

void exec_images_release(struct thread *t);
void exec_images_activate(struct thread *t);
void release_operation(struct thread *t);
struct thread *find_thread(pid_t pid);
struct thread *thread(pid_t pid);
void pid_birth_release(struct thread *child);
void pid_store_add(struct thread *t, uintptr_t address, pid_t value);
void release_thread(struct thread *t);
int image_enter(struct thread *t,uintptr_t image,uintptr_t address,size_t count,unsigned long machine);
void image_request(struct thread *t, const struct seccomp_notif *q);
int birth_stop_pending(const struct thread *t);
void task_buffer_control(struct thread *t,const struct supervisor_control_request *control);
pid_t visible_pid(struct thread *t, pid_t native);
pid_t native_pid(struct thread *t,pid_t visible);
void process_reboot_info(pid_t pid,int *code,int *status);
int process_call(struct thread *t,const struct seccomp_data *call,struct user_pt_regs *r,unsigned long *cookie);
int freezer_parked(const struct thread *t);
void freezer_progress(void);
int private_wait_children(pid_t pid,const struct seccomp_data *call,pid_t **candidates,size_t *count);
int pid_gone(pid_t pid);
void pid_collect(void);
void task_terminal_event(struct thread *t,pid_t pid,int status);
void task_exit_event(struct thread *t,pid_t pid,unsigned event);
void task_vfork_done_event(struct thread *t,pid_t pid,unsigned event);
void task_exec_event(struct thread *t,pid_t pid,unsigned event);
void task_birth_event(struct thread *t,pid_t pid,unsigned event);
void task_stop_event(struct thread *t,pid_t pid,int status,int sig);
int task_clone_exit(struct thread *t,pid_t pid);
int task_process_exit(struct thread *t,pid_t pid);
void task_wait_complete(struct thread *t,long result);
void pid_store_begin(struct thread *t);

#endif
