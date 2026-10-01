#ifndef MD_FS_RPC_H
#define MD_FS_RPC_H
#include "fs_operation.h"
#include "image_identity.h"
#include <linux/limits.h>

enum md_fs_delivery { MD_FS_NOT_SENT, MD_FS_UNCONFIRMED, MD_FS_REPLIED };
struct md_fs_response {
    enum md_fs_delivery delivery;
    struct md_fs_result result;
    char data[sizeof(struct md_image_identity)];
};
/* One connection per call: no shared socket, lock, heap, TLS errno or replay.
 * Return value is transport/protocol status; response.result.error is the remote errno.
 * UNCONFIRMED means a mutation may have committed. A timeout is not cancellation
 * of the remote operation. Success transfers ownership of response.result.fd to caller.
 * Inputs are adapter-owned memory, not unchecked guest pointers. */
long md_fs_call(const char *abstract_name, unsigned timeout_ms,
        const struct md_fs_request *, struct md_fs_response *);
#endif
