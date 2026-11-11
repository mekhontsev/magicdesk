#ifndef MD_GUEST_EXECUTION_H
#define MD_GUEST_EXECUTION_H
#include <stdint.h>

enum md_dispatch_policy { MD_DISPATCH_PATHS=1, MD_DISPATCH_NETWORK_SCOPED=2, MD_DISPATCH_OFFLINE_SOCKET=4,
    MD_DISPATCH_NETWORK_CONTROL=8, MD_DISPATCH_NETWORK_DATA=16, MD_DISPATCH_DEVICE_EVENTS=32 };
enum md_execution_action { MD_EXECUTION_RESULT, MD_EXECUTION_GUEST,
    MD_EXECUTION_CONTEXT };
/* A context request precedes effects. The resident frame restores the original
 * call, then its existing owner performs the operation at the kernel boundary.
 * Internal continuations instead retain their operation through NEEDS_STOP. */
enum md_execution_context { MD_CONTEXT_COPY, MD_CONTEXT_PROCESS, MD_CONTEXT_BIRTH,
    MD_CONTEXT_EXEC, MD_CONTEXT_SIGNAL, MD_CONTEXT_WAIT, MD_CONTEXT_DESCRIPTOR,
    MD_CONTEXT_MEMORY, MD_CONTEXT_POLICY, MD_CONTEXT_NAMESPACE, MD_CONTEXT_DEBUG,
    MD_CONTEXT_COUNT };
/* Pure operation requirements, shared by local ingress and the execution
 * owner. COUNT means no known requirement, never an admission allow-list. */
enum md_execution_context md_execution_context_needed(long,const uint64_t [6],unsigned);

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
