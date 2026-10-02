#ifndef MD_SYSV_IPC_H
#define MD_SYSV_IPC_H
#include "guest_identity.h"
#include <stdint.h>
#include <time.h>

struct md_ipc;
struct md_ipc_pending;
struct md_ipc_undo;
enum { MD_IPC_OPEN, MD_IPC_EXECUTE, MD_IPC_CLOSE };
struct md_ipc_packet {
    size_t length, input_size, timeout_offset, output_size;
    unsigned char data[];
};
struct md_ipc_request { long nr; unsigned long args[6]; };
struct md_ipc_transport {
    int fd;
    struct md_ipc_packet *packet;
    size_t length, capacity;
    struct md_ipc_request call;
};
int md_ipc_transport_open(struct md_ipc *, struct md_ipc_transport *,
    const struct md_ipc_request *, int retry);
void md_ipc_transport_close(struct md_ipc_transport *);

int md_ipc_open(const char *, struct md_ipc **);
void md_ipc_close(struct md_ipc *);
struct md_ipc_undo *md_ipc_undo_new(void);
struct md_ipc_undo *md_ipc_undo_fork(struct md_ipc_undo *, int shared);
int md_ipc_undo_release(struct md_ipc *, struct md_ipc_undo *);
long md_ipc_call(struct md_ipc *, struct md_ipc_pending **, struct md_ipc_undo *,
    const struct md_identity *, pid_t tid, pid_t tgid, struct md_ipc_transport *);
int md_ipc_cancel(struct md_ipc *, struct md_ipc_pending **);
int md_ipc_wait_fd(const struct md_ipc_pending *);
int64_t md_ipc_deadline(const struct md_ipc_pending *);
#endif
