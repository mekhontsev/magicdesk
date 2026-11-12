#ifndef MD_SVC_RUNTIME_H
#define MD_SVC_RUNTIME_H
#include <stdint.h>
#include "cooperative_native.h"
enum { MD_SVC_CODE_POOLS=8, MD_SVC_CODE_BYTES=65536 };
/* Bootstrap-owned resources, captured once at image admission. */
struct md_svc_runtime {
    uintptr_t frames, dispatch, checkpoint;
    struct md_native_gate native;
    uintptr_t pools[MD_SVC_CODE_POOLS];
    unsigned count;
    int error;
};
extern struct md_svc_runtime md_svc_runtime;
void md_svc_runtime_prepare(uintptr_t program,uintptr_t loader,unsigned long capabilities,int statistics);
#endif
