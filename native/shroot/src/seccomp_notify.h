#ifndef MD_SECCOMP_NOTIFY_H
#define MD_SECCOMP_NOTIFY_H
#include <linux/seccomp.h>
#include "control_file.h"

/* Launch-local notification state. Only selected USER_NOTIF calls enter this
 * owner; ordinary syscalls and data IO do not inspect it. The coordinator owns
 * caller cancellation, listener lifetime and original-policy admission. */
struct md_seccomp_notify;
struct md_seccomp_notification;
struct md_pid_identity;
struct md_fd_origins;
struct md_seccomp_notify *md_seccomp_notify_new(struct md_resource_wakeup *);
struct md_seccomp_notify *md_seccomp_notify_retain(struct md_seccomp_notify *);
void md_seccomp_notify_release(struct md_seccomp_notify *);
/* Last listener close replies ENOSYS to outstanding callers, but never replaces
 * an already committed SEND. Requests do not retain the listener descriptor. */
void md_seccomp_notify_close(struct md_seccomp_notify *);
/* Whether any task still retains the filter; poll HUP is independent of close. */
void md_seccomp_notify_active(struct md_seccomp_notify *,int active);
/* Snapshot caller PID numbers once. Each RECV uses its own captured namespace;
 * SCM_RIGHTS does not retain the opener's PID view or expose a foreign PID. */
int md_seccomp_notify_begin(struct md_seccomp_notify *,unsigned pid,const struct md_pid_identity *,
    const struct seccomp_data *,struct md_seccomp_notification **);
/* Bind before exposing the request. The launch owns this listener through
 * request retirement. Replies commit directly against this exact kernel ID;
 * no sender memory or event-loop latency separates SEND from its commit.
 * Borrowed calls encode CONTINUE as a completed zero-valued carrier; original
 * calls retain the kernel wait until their coordinator admits the effect. */
struct md_seccomp_notification_kernel {
    int listener,borrowed;
    uint64_t id;
    /* Captured target table and close credentials, not the listener's opener. */
    struct md_fd_origins *files;
    const struct md_identity *identity;
};
int md_seccomp_notify_begin_kernel(struct md_seccomp_notify *,unsigned pid,const struct md_pid_identity *,
    const struct seccomp_data *,const struct md_seccomp_notification_kernel *,struct md_seccomp_notification **);
/* 1 committed result, 0 pending, negative error. Copy is independent of sender
 * memory/lifetime. Caller termination releases its exact request, not an ID. */
int md_seccomp_notification_result(struct md_seccomp_notification *,struct seccomp_notif_resp *);
void md_seccomp_notification_release(struct md_seccomp_notification *);
/* Borrowed typed ioctl/readiness provider. RECV requires the common terminal
 * copy acknowledgement; disposal before acknowledgement requeues the message. */
struct md_control_file md_seccomp_notify_control(struct md_seccomp_notify *);
#endif
