#ifndef MD_PROCESS_OOM_H
#define MD_PROCESS_OOM_H
#include "guest_identity.h"
#include <stddef.h>
#include <stdint.h>

/* Thread-group policy, copied on fork and preserved across exec. A virtual
 * value does not alter Android's OOM protection or memory accounting. */
struct md_process_oom { int value, minimum, virtualized; };
long md_process_oom_io(struct md_process_oom *,const struct md_identity *,int legacy,
    int writing,int native_value_fd,int native_update_fd,int native_write_error,void *,size_t,int64_t);
#endif
