#ifndef MD_GUEST_EXECUTION_H
#define MD_GUEST_EXECUTION_H
#include <stdint.h>

enum md_dispatch_policy { MD_DISPATCH_PATHS=1, MD_DISPATCH_NETWORK_SCOPED=2, MD_DISPATCH_OFFLINE_SOCKET=4,
    MD_DISPATCH_NETWORK_CONTROL=8, MD_DISPATCH_NETWORK_DATA=16, MD_DISPATCH_DEVICE_EVENTS=32 };

/* An admitted operation, independent of the ingress register ABI. The owner
 * retains the typed control OFD and prepared input until execution completes;
 * neither is a cache or authority supplied by the application. */
struct md_guest_execution {
    long number;
    unsigned long args[6];
    unsigned policy;
    int control;
    uintptr_t prepared;
};
long md_guest_execute(const struct md_guest_execution *);

#define MD_WATCH_TASK_AFFINE (-INT64_C(0x4000000000000000))
#define MD_WATCH_WAIT (MD_WATCH_TASK_AFFINE + 1)
/* Finish adapter ownership before a native call can block or deliver signals. */
#define MD_CALL_NATIVE (MD_WATCH_TASK_AFFINE + 2)
#define MD_IPC_WAIT (MD_WATCH_TASK_AFFINE + 3)
#define MD_CALL_CLONE (MD_WATCH_TASK_AFFINE + 4)
#endif
