#ifndef MD_NETWORK_IO_H
#define MD_NETWORK_IO_H
#include "network_endpoint.h"
#include "ipc_io_host.h"
#include <poll.h>
struct md_network_io {
    struct md_fd_object *endpoint;
    struct md_identity *identity;
    long number;
    unsigned long args[6];
    struct sockaddr_storage destination;
    socklen_t length;
    int started;
    struct pollfd connect_wait;
};
/* Uses the common captured-FD, cancellation and readiness owner. */
int md_network_io_sending(const struct md_network_io *);
int md_network_io_flags(const struct md_network_io *);
int md_network_io_prepare_batch(struct md_ipc_io *,struct md_ipc_io_message *,int,int,unsigned);
int md_network_io_select(struct md_network_io *,struct md_ipc_io *,struct md_ipc_io_message *,unsigned);
int md_network_io_progress(struct md_network_io *,struct md_ipc_io *,struct md_received_fds **,
    struct md_ipc_io_message *,long *);
void md_network_io_release(struct md_network_io *,struct md_ipc_io *);
#endif
