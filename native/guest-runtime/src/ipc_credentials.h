#ifndef MD_IPC_CREDENTIALS_H
#define MD_IPC_CREDENTIALS_H
#include "guest_identity.h"
#include "fs_operation.h"

/* Store-scoped, live IPC metadata. Kernel sockets own data and descriptor
 * lifetimes; this authority owns only connection-time identity snapshots. */
struct md_ipc_credentials;
struct md_ipc_identity {
    int32_t pid;
    uint32_t uid, gid, group_count;
};
enum md_ipc_operation {
    MD_IPC_LISTEN_BEGIN = 1, MD_IPC_LISTEN_END, MD_IPC_CONNECT_BEGIN,
    MD_IPC_CONNECT_END, MD_IPC_ACCEPT, MD_IPC_PAIR, MD_IPC_PEER, MD_IPC_NAME,
    MD_IPC_MESSAGE_CREATE, MD_IPC_MESSAGE_READ
};
int md_ipc_credentials_open(const char *store, struct md_ipc_credentials **);
void md_ipc_credentials_close(struct md_ipc_credentials *);
void md_ipc_credentials_execute(struct md_ipc_credentials *, const struct md_identity *,
    const struct md_fs_request *, struct md_fs_result *, const struct md_fs_output *);
#endif
