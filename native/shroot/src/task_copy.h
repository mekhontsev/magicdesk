#ifndef MD_TASK_COPY_H
#define MD_TASK_COPY_H
#include "task_call.h"
#include <stddef.h>
#include <sys/uio.h>

/* A caller-owned shared scratch span. The owner retains its mapping turn and
 * buffer loan until native completion or confirmed actor death. The peer must
 * not inherit the supervisor's writable pointer as its virtual address. */
struct md_task_copy_scratch { struct iovec local,remote; unsigned char bytes[]; };
struct md_task_copy {
    struct md_task_call call;
    struct md_task_copy_scratch *scratch;
    void *data;
    size_t size;
    ssize_t result;
    int writing;
};
/* Permission-respecting fast path. Partial copies retain their native count;
 * EPERM/EACCES may use self-copy, but EFAULT must not use forced proc writes. */
ssize_t md_task_copy_direct(pid_t,uintptr_t,void *,size_t,int writing);
/* Only after direct access was denied. The existing task-call owner performs a
 * fault-aware process_vm_* self-copy in the exact actor, preserving dumpability
 * and application signal dispositions. No helper thread or private wait loop.
 * The enclosing policy owner must admit this service call, not reinterpret it
 * as a new application operation. Zero starts; negative results do not run. */
int md_task_copy_begin(struct md_task_copy *,long (*trace)(int,pid_t,void *,void *),
    pid_t,enum md_task_call_boundary,const struct user_pt_regs *,uintptr_t call,uintptr_t returned,
    uintptr_t address,void *data,size_t size,int writing,void *scratch,uintptr_t peer,size_t capacity);
/* Same event contract as md_task_call_event. A completed result is a native
 * byte count/negative errno. The caller may then release scratch and data. */
int md_task_copy_event(struct md_task_copy *,int status);
#endif
