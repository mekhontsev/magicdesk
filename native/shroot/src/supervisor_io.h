#ifndef MD_SUPERVISOR_IO_H
#define MD_SUPERVISOR_IO_H

#include "supervisor_thread.h"
#include "task_outgoing.h"
#include "ipc_io_host.h"
#include "network_io.h"
#include "ipc_origins.h"
#include "fs_engine.h"

enum outgoing_stage { OUTGOING_QUEUED, OUTGOING_CAPTURE, OUTGOING_PREPARE, OUTGOING_READY, OUTGOING_SEND };

struct outgoing_owner {
    pid_t caller;
    enum outgoing_stage stage;
    struct user_pt_regs continuation;
    struct md_task_send input;
    struct md_fd_order files;
    struct md_fs_order fs_order;
    struct md_fs_context context;
    struct md_ipc_send_options options;
    struct md_ipc_send_address destination;
    struct md_fd_message *message;
    struct md_ipc_origin_request *preparation;
    struct md_received_fds *prepared;
    struct md_fd_capture capture;
    int brokered, io_waiting;
    struct md_ipc_io io;
};

struct receive_owner {
    pid_t caller;
    int started, ready, prepared;
    struct user_pt_regs continuation;
    struct md_task_receive request;
    struct md_fd_order files;
    struct md_received_fds *received;
    struct md_received_fds *expanded;
    struct receive_publication *publication;
    int outgoing[253];
    struct md_fd_capture capture;
    int brokered, io_waiting;
    struct md_ipc_io io;
    struct md_network_io network;
};

void receive_release(struct receive_owner *owner);
void receive_discard(struct thread *t);
void outgoing_discard(struct thread *t);
void outgoing_release(struct outgoing_owner *owner);
void control_publication_finish(struct thread *t,long result);
void control_pending_complete(struct thread *t,long result);
long resource_control(struct thread *t,unsigned service,unsigned operation,const unsigned long args[4]);
void ipc_entry_wait(struct thread *t);
void resource_request(struct thread *t,const struct seccomp_notif *q);
void ipc_unwatch(struct thread *t);
void ipc_events_ready(void);
void wait_poll_start(struct thread *t,enum phase phase);
int wait_poll_entry(struct thread *t);
void control_park(struct thread *t);
void control_reply(struct thread *t,long result);
struct thread *control_deadline_owner(void);
void control_progress(struct thread *t);
void outgoing_start(struct thread *t);
void socket_unwatch(struct thread *t);
int socket_watch(struct thread *t);
void socket_events_ready(void);
void receive_start(struct thread *t);
void io_buffer_control(struct thread *t,const struct supervisor_control_request *control,const struct user_pt_regs *r);
int receive_prepared(struct thread *t,const struct user_pt_regs *continuation,unsigned policy);
int receive_entry(struct thread *t);
void ipc_wait_complete(struct thread *t);
void socket_wait_complete(struct thread *t);
void socket_wait_cancelled(struct thread *t);
void control_wait_complete(struct thread *t);
int control_wait_exit(struct thread *t,pid_t pid);
int socket_wait_exit(struct thread *t,pid_t pid);
int ipc_wait_exit(struct thread *t,pid_t pid);

#endif
