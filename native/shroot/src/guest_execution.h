#ifndef MD_GUEST_EXECUTION_H
#define MD_GUEST_EXECUTION_H
#define MD_EXECUTION_POLICY_OFFSET 56
#define MD_EXECUTION_CONTROL_OFFSET 60
#define MD_EXECUTION_PREPARED_OFFSET 64
#define MD_EXECUTION_SIZE 72

#ifndef __ASSEMBLER__
#include <stdint.h>
#include <stddef.h>

enum md_dispatch_policy { MD_DISPATCH_PATHS=1, MD_DISPATCH_NETWORK_SCOPED=2, MD_DISPATCH_OFFLINE_SOCKET=4,
    MD_DISPATCH_NETWORK_CONTROL=8, MD_DISPATCH_NETWORK_DATA=16, MD_DISPATCH_DEVICE_EVENTS=32 };
enum md_execution_action { MD_EXECUTION_RESULT, MD_EXECUTION_GUEST,
    MD_EXECUTION_CONTEXT, MD_EXECUTION_NATIVE };
/* A context request precedes effects. The resident frame restores the original
 * call, then its existing owner performs the operation at the kernel boundary.
 * Internal continuations instead retain their operation through NEEDS_STOP. */
enum md_execution_context { MD_CONTEXT_PROCESS, MD_CONTEXT_BIRTH,
    MD_CONTEXT_EXEC, MD_CONTEXT_SIGNAL, MD_CONTEXT_WAIT, MD_CONTEXT_DESCRIPTOR,
    MD_CONTEXT_MEMORY, MD_CONTEXT_POLICY, MD_CONTEXT_NAMESPACE, MD_CONTEXT_DEBUG,
    MD_CONTEXT_COUNT };
/* Native-effect requirements, not an ingress admission policy. WAIT selects
 * the signal-aware native journal; it does not imply a ptrace stop. */
enum md_execution_context md_execution_native_context(long,const uint64_t [6]);
/* Remaining C-adapter continuation requirements. Resource owners make their
 * own explicit stop requests before this adapter is selected. */
struct md_guest_execution;
enum md_execution_context md_execution_dispatch_context(const struct md_guest_execution *);

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
_Static_assert(offsetof(struct md_guest_execution,number)==0 && offsetof(struct md_guest_execution,args)==8,
    "stopped entry argument layout");
_Static_assert(offsetof(struct md_guest_execution,policy)==MD_EXECUTION_POLICY_OFFSET
    && offsetof(struct md_guest_execution,control)==MD_EXECUTION_CONTROL_OFFSET
    && offsetof(struct md_guest_execution,prepared)==MD_EXECUTION_PREPARED_OFFSET
    && sizeof(struct md_guest_execution)==MD_EXECUTION_SIZE,"stopped entry execution layout");
long md_guest_execute(const struct md_guest_execution *);
/* Internal effects of an admitted C operation use the resident journal or
 * the existing Base gate. A cancelled effect requests the original restart
 * boundary only after its C owner has released its temporary resources. */
enum call_restart { RESTART_SIGNAL=-4105, RESTART_PREPARATION=-4106 };
long md_guest_native_call(long,const unsigned long [6]);

#define MD_WATCH_TASK_AFFINE (-INT64_C(0x4000000000000000))
#define MD_WATCH_WAIT (MD_WATCH_TASK_AFFINE + 1)
/* Finish adapter ownership before a native call can block or deliver signals. */
#define MD_CALL_NATIVE (MD_WATCH_TASK_AFFINE + 2)
#define MD_IPC_WAIT (MD_WATCH_TASK_AFFINE + 3)
#define MD_CALL_CLONE (MD_WATCH_TASK_AFFINE + 4)
#endif
#endif
