#ifndef MD_FD_CAPTURE_H
#define MD_FD_CAPTURE_H
#include "fd_channel.h"
#include "fd_origins.h"
#include "received_fds.h"

enum { MD_FD_CAPTURE_SEND=1, MD_FD_CAPTURE_ADMIT, MD_FD_CAPTURE_CHECK };
struct md_fd_capture_frame {
    uint64_t token;
    int endpoint;
    unsigned offset, count;
    struct md_fd_channel_observation observation;
};
/* The enclosing operation owns the table turn through all exports. Captures
 * contain only exact descriptions; no descriptor is reopened by pathname. */
struct md_fd_capture {
    struct md_fd_channel *channel;
    struct md_fd_channel_capture packet;
    struct md_received_fds *result;
    unsigned count, offset;
    int endpoint, checking;
};
/* Under the same table turn: 1 transfers exact native custody, 0 needs the
 * task-affine export, negative errno fails. A partial vector never escapes.
 * The task owner retains process under the task_descriptor lifetime contract. */
int md_fd_capture_native(pid_t,int *process,const int *,unsigned,struct md_received_fds **);
int md_fd_capture_progress(struct md_fd_capture *, struct md_fd_origins *, pid_t,
        unsigned count, struct md_fd_capture_frame *);
int md_fd_capture_observed(struct md_fd_capture *, struct md_fd_origins *, const struct md_fd_capture_frame *);
int md_fd_capture_admit(struct md_fd_capture *, struct md_fd_origins *, int listener, uint64_t id);
int md_fd_capture_sent(struct md_fd_capture *, long result);
struct md_received_fds *md_fd_capture_take(struct md_fd_capture *);
void md_fd_capture_cancel(struct md_fd_capture *);
#endif
