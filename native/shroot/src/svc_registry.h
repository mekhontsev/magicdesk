#ifndef MD_SVC_REGISTRY_H
#define MD_SVC_REGISTRY_H
#include "svc_payload.h"
#include "svc_runtime.h"
#include "svc_direct.h"
#include <sys/types.h>

enum { MD_SVC_SITES=128 };
struct md_svc_code_pool { uintptr_t address; size_t used; };
struct md_svc_site {
    struct md_svc_direct_context direct;
    uintptr_t kernel_alias;
    uint32_t original, replacement;
    unsigned state;
};
enum { MD_SVC_PUBLISHED, MD_SVC_RETIRED };
/* Owned by interception_memory. CLONE_VM shares the owner; fork copies it.
 * No guest-writable receipt is authority for code publication or retirement. */
struct md_svc_registry {
    uintptr_t frames, dispatch, native_begin, native_end;
    struct md_svc_code_pool pools[MD_SVC_CODE_POOLS];
    unsigned pool_count, count, disabled, entry_fence_drained, clients_dirty, frames_dirty;
    pid_t sole_owner;
    unsigned identity_known;
    struct md_svc_site sites[MD_SVC_SITES];
};
struct md_svc_registry *md_svc_registry_new(uintptr_t frames,uintptr_t dispatch);
struct md_svc_registry *md_svc_registry_copy(const struct md_svc_registry *);
int md_svc_registry_pool(struct md_svc_registry *,uintptr_t);
int md_svc_registry_read(pid_t,uintptr_t,void *,size_t);
int md_svc_registry_write(pid_t,uintptr_t,const void *,size_t);
/* Unpublished code only: partial writes may be retried before its branch is
 * installed. Live instruction publication still uses the word writer. */
int md_svc_registry_stage(pid_t,int memory_fd,uintptr_t,const void *,size_t);
int md_svc_registry_executable(pid_t,uintptr_t,size_t);
int md_svc_registry_direct(struct md_svc_registry *,pid_t,int memory_fd,uintptr_t svc,uint64_t number);
/* Conservative context test from an already captured PC/SP. Retired code and
 * private stacks still require recovery; ordinary caller code needs no GPR read. */
int md_svc_registry_context(const struct md_svc_registry *,uintptr_t pc,uintptr_t sp);
struct md_svc_site *md_svc_registry_direct_at(struct md_svc_registry *,pid_t,uintptr_t pc,uintptr_t sp);
uintptr_t md_svc_registry_call_origin(const struct md_svc_registry *,uintptr_t pc);
/* Decode a captured word for syscall replay, never arbitrary code at a former
 * site. Only an exact resident replacement has an owned original instruction. */
uint32_t md_svc_registry_instruction(const struct md_svc_registry *,uintptr_t pc,uint32_t word);
int md_svc_registry_retire(struct md_svc_registry *,struct md_svc_site *,pid_t);
int md_svc_registry_revoke(struct md_svc_registry *,pid_t);
/* Retired source instructions can still have peers inside resident gates. */
int md_svc_registry_resident(const struct md_svc_registry *);
/* After revocation, occupied frames bound pre-revocation API execution.
 * New claims must observe revoked admission before issuing private calls. */
int md_svc_registry_occupied(const struct md_svc_registry *,pid_t,uint64_t *);
/* A copied mm has no peers. Clone and signal delivery leave private entry
 * before application execution; discard copied claims before child resume. */
int md_svc_registry_prepare_fork(struct md_svc_registry *,pid_t,int memory_fd);
int md_svc_registry_unpatch(struct md_svc_registry *,pid_t,uintptr_t,size_t);
/* Retry incomplete retirement through any stopped member of this same mm.
 * Liveness comes from the supervisor, never from a guest-writable handle. */
int md_svc_registry_collect_clients(struct md_svc_registry *,pid_t,int memory_fd,
    int (*alive)(pid_t,void *),void *);
/* Update at an owned stop, before a newborn or its parent can enter userspace.
 * Zero removes the hint; shared address spaces always query the kernel. */
int md_svc_registry_identity(struct md_svc_registry *,pid_t,int memory_fd,pid_t sole_owner);
int md_svc_registry_suspend(struct md_svc_registry *,pid_t,uintptr_t svc);
/* Restore an admitted head only. Extending a chain requires an explicit
 * direct admission with the original call's arguments and current policy. */
int md_svc_registry_resume(struct md_svc_registry *,pid_t,uintptr_t svc);
#endif
