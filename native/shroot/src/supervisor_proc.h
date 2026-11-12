#ifndef MD_SUPERVISOR_PROC_H
#define MD_SUPERVISOR_PROC_H

#include "supervisor_thread.h"

struct proc_owner {
    pid_t pid;
    const struct md_identity *identity;
    const struct md_task_resources *resources;
    struct md_pid_namespace *pid_namespace;
    struct md_cgroup_member *cgroup;
    struct md_process_member *process;
};

void proc_query_begin(struct thread *t, const struct seccomp_notif *q, int target);
void proc_query_start(struct thread *t);
void proc_deliver(struct thread *t);
void proc_snapshot_complete(struct thread *t,struct md_fs_buffer_request *request,int error);
struct md_proc_file *proc_file_capture(struct thread *t, int fd);
struct proc_owner proc_owner(pid_t pid);
int control_file_query(struct md_fd_object *object,unsigned operation,struct md_control_file *out);
int control_file_capture(pid_t actor,const char *path,struct md_fd_object **out);
int executable_capture(pid_t actor,struct md_program *image);
int proc_path_capture(pid_t actor,pid_t process,unsigned kind,struct md_task_snapshot *snapshot);
void proc_io_progress(struct thread *t);
void proc_io_begin(struct thread *t);
int proc_write_direct(struct thread *t);
void proc_buffer_control(struct thread *t,const struct supervisor_control_request *control);
void proc_lookup_start(struct thread *t);
void proc_lookup_begin(struct thread *t,const struct seccomp_data *call,const struct user_pt_regs *regs);

#endif
