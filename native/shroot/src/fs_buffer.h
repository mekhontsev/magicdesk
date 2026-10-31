#ifndef MD_FS_BUFFER_H
#define MD_FS_BUFFER_H
#include "fs_rpc.h"
#include "fd_receipt.h"
#include "command_image.h"
#include "fd_channel.h"
#include "received_info.h"
#include <fcntl.h>

enum md_fs_buffer_control { MD_FS_BUFFER_CALL=16, MD_FS_BUFFER_ACK, MD_FS_BUFFER_SENT,
    MD_FS_BUFFER_EXEC_PREPARE, MD_FS_BUFFER_EXEC_CANCEL,
    MD_FS_BUFFER_SNAPSHOT_BEGIN, MD_FS_BUFFER_SNAPSHOT_APPEND, MD_FS_BUFFER_SNAPSHOT_FINISH,
    MD_FS_BUFFER_RIGHTS_BEGIN, MD_FS_BUFFER_RIGHTS_READY, MD_FS_BUFFER_RIGHTS_DONE,
    MD_FS_BUFFER_CHANNEL_OBSERVED, MD_FS_BUFFER_PROC_LINK, MD_FS_BUFFER_TASK_DONE,
    MD_FS_BUFFER_TASK_RECEIVE, MD_FS_BUFFER_RECEIVED_INSPECT,
    MD_FS_BUFFER_RECEIVED_RIGHTS, MD_FS_BUFFER_RECEIVED_DROP,
    MD_FS_BUFFER_SEND_PREPARE, MD_FS_BUFFER_SEND_COMMIT,
    MD_FS_BUFFER_IPC_CAPTURE_READY, MD_FS_BUFFER_IPC_CAPTURE_SENT,
    MD_FS_BUFFER_IPC_CAPTURE_OBSERVED, MD_FS_BUFFER_IPC_RECEIVE,
    MD_FS_BUFFER_IPC_IO_PREPARE, MD_FS_BUFFER_IPC_IO_COPY, MD_FS_BUFFER_IPC_IO_SELECT,
    MD_FS_BUFFER_RESULT_REPLAY };
/* These completions return to the C adapter, not to application registers. */
static inline int md_fs_buffer_scalar(unsigned long command) {
    return command==MD_FS_BUFFER_CALL || command==MD_FS_BUFFER_EXEC_PREPARE
        || command==MD_FS_BUFFER_EXEC_CANCEL || command==MD_FS_BUFFER_PROC_LINK
        || command==MD_FS_BUFFER_IPC_IO_PREPARE || command==MD_FS_BUFFER_IPC_IO_COPY
        || command==MD_FS_BUFFER_IPC_IO_SELECT;
}
enum { MD_FS_BUFFER_SOURCE=-2 };
/* Kernel ancillary FDs do not index a capsule-expanded application vector. */
enum md_received_source { MD_RECEIVED_KERNEL, MD_RECEIVED_APPLICATION };
enum md_fs_buffer_transfer { MD_FS_BUFFER_NO_FD, MD_FS_BUFFER_ANCHOR, MD_FS_BUFFER_ADDFD, MD_FS_BUFFER_RIGHTS,
    MD_FS_BUFFER_TASK_PATH, MD_FS_BUFFER_CALLER_OPEN, MD_FS_BUFFER_REPLAY_ADDFD };
enum { MD_FS_BUFFER_EXPORT=2, MD_FS_BUFFER_IMPORT=3, MD_FS_BUFFER_CHANNEL_CHECK=4,
    MD_FS_BUFFER_IPC_ADMIT=5, MD_FS_BUFFER_RESULT_ADMIT=6, MD_FS_BUFFER_EPOLL_ADMIT=7,
    MD_FS_BUFFER_EPOLL_LOAN=8 };
struct md_fs_buffer_input {
    int base[2];
    unsigned flags, mode, capacity, value_size;
    uint64_t resolve;
    int64_t offset;
    struct md_fs_attributes attributes;
    size_t length[2];
    char object[2][33];
    char path[2][PATH_MAX];
    size_t capture_length[2];
    unsigned capture_flags[2];
    char capture_path[2][PATH_MAX];
};
struct md_fs_buffer_frame {
    union {
        struct md_fs_buffer_input input;
        struct md_fs_response reply;
        struct md_command_recipe command;
        struct { size_t size; char data[8192]; } snapshot;
    };
    unsigned transfer;
    /* Internal O_PATH reopen flags, validated against supervisor-owned custody. */
    unsigned source_flags;
    unsigned source_verify;
    uint64_t source_device, source_inode;
    struct { uint64_t request; unsigned mask; int number; } export;
    char source[64+NAME_MAX];
    struct { uint64_t token; int number; unsigned mapped; } receive;
    struct { unsigned count; enum md_received_source source; int index[253]; } rights;
    struct { int number; struct md_fd_channel_observation observation; } channel;
    _Alignas(struct md_fd_receipt) unsigned char receipt[
        offsetof(struct md_fd_receipt,control)+CMSG_SPACE(MD_FD_RECEIPT_MAX*sizeof(int))];
    /* Faulted and copied only for explicit value operations, not ordinary IO. */
    union {
        unsigned char value[MD_FS_OUTPUT_MAX];
        struct md_received_message received_message;
    };
};
static inline int md_fs_buffer_source_matches(const struct md_fs_buffer_frame *frame,
        uint64_t device,uint64_t inode) {
    return !frame->source_verify || (device==frame->source_device && inode==frame->source_inode);
}
/* Bootstrap-only FD setup; ordinary calls exchange registers and mapped bytes. */
#ifndef MD_USE_LIBC
long md_guest_buffer_result(unsigned flags,unsigned mode,struct md_fs_buffer_frame *);
int md_fs_buffer_accepts(const struct md_fs_request *);
long md_fs_buffer_exec_prepare(const struct md_fs_request *, struct md_command_recipe *, unsigned long);
void md_fs_buffer_exec_cancel(void);
/* Producer copies into mapped bytes; only the completed file enters the guest. */
long md_fs_buffer_snapshot(unsigned flags, long (*produce)(void *, void *, size_t), void *);
long md_fs_buffer_proc_link(int process, void *out, size_t capacity);
long md_fs_buffer_call(const struct md_fs_request *, struct md_fs_response *,
        int (*deliver)(void *, const void *, size_t), void *context);
long md_fs_buffer_inspect(const int *, unsigned, struct md_received_message *);
long md_fs_buffer_publish(enum md_received_source, const int *, const unsigned char *, unsigned,
    void *, size_t, unsigned, int *, size_t *);
#endif
#endif
