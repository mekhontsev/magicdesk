#ifndef MD_ADAPTER_BUFFER_H
#define MD_ADAPTER_BUFFER_H
#include <stddef.h>

enum { MD_ADAPTER_BUFFER_SLOTS=32, MD_ADAPTER_CONTROL_SLOTS=1, MD_ADAPTER_CHANNEL_SLOTS=8 };
/* A provider read filled one transport page, not the complete syscall. */
#define MD_BUFFER_PROC_MORE (1UL<<32)
enum md_adapter_buffer_control { MD_BUFFER_MAPPED=1, MD_BUFFER_TAKE, MD_BUFFER_RELEASE,
    MD_BUFFER_IPC_PREPARE, MD_BUFFER_SHM_STAT, MD_BUFFER_PROBE, MD_BUFFER_UTS,
    MD_BUFFER_PROC_IO, MD_BUFFER_PROC_ACK, MD_BUFFER_PROC_RIGHTS, MD_BUFFER_PROC_META,
    MD_BUFFER_PID_NUMBER, MD_BUFFER_DESCRIPTOR_META, MD_BUFFER_MEMORY_OPEN,
    MD_BUFFER_CONTROL_IOCTL_SPEC=80, MD_BUFFER_CONTROL_IOCTL, MD_BUFFER_PIDFD_NAMESPACE,
    MD_BUFFER_CONTROL_MAP, MD_BUFFER_CONTROL_IOCTL_LAYOUT,
    MD_BUFFER_NETWORK_OPEN=64, MD_BUFFER_NETWORK_CALL, MD_BUFFER_NETWORK_DATA, MD_BUFFER_NETWORK_CREDENTIALS,
    MD_BUFFER_EPOLL_BEGIN=90, MD_BUFFER_EPOLL_CAPTURE_READY, MD_BUFFER_EPOLL_CAPTURE_SENT,
    MD_BUFFER_EPOLL_CAPTURE_OBSERVED, MD_BUFFER_EPOLL_WAIT,
    MD_BUFFER_POLL_BEGIN, MD_BUFFER_POLL_LOAD, MD_BUFFER_POLL_COPY, MD_BUFFER_MEMORY_OPEN_RESULT,
    MD_BUFFER_FINISH, MD_BUFFER_PROC_START, MD_BUFFER_PIDFD_SIGNAL,
    MD_BUFFER_PIDFD_READY, MD_BUFFER_PIDFD_SENT, MD_BUFFER_PIDFD_OBSERVED, MD_BUFFER_PIDFD_ADMIT,
    MD_BUFFER_CLONE_CGROUP };
/* These operations always resume the C caller with a scalar return value.
 * They neither restore application registers nor inject task-context calls. */
static inline int md_adapter_buffer_scalar(unsigned long command) {
    return command==MD_BUFFER_MAPPED || command==MD_BUFFER_TAKE
        || command==MD_BUFFER_RELEASE;
}
struct md_adapter_input { long value; void *buffer; };
#ifndef MD_USE_LIBC
int md_adapter_buffer_install(void);
int md_adapter_buffer_ready(void);
long md_adapter_buffer_take(void);
long md_adapter_buffer_release(void);
/* Terminal acknowledgement retires the buffer and completes the syscall. */
long md_adapter_buffer_finish(long copied, long result);
/* Operations without an input payload can acquire and execute together. */
struct md_adapter_input md_adapter_buffer_start(size_t length);
struct md_adapter_input md_guest_buffer_start(unsigned, unsigned long, unsigned long);
long md_guest_buffer_control(unsigned, unsigned long, unsigned long);
struct md_fs_buffer_frame;
long md_adapter_receive_fd(struct md_fs_buffer_frame *, unsigned flags, unsigned begin);
#endif
#endif
