#ifndef MD_INTERCEPTION_STACKS_H
#define MD_INTERCEPTION_STACKS_H
#include <stdint.h>

/* Supervisor-owned metadata for mappings in one tracee address space. Dead
 * threads return their guarded stack for reuse; exec releases the old space. */
struct md_interception_stacks;
struct md_interception_stacks *md_stacks_new(void);
struct md_interception_stacks *md_stacks_fork(struct md_interception_stacks *, int shared);
void md_stacks_release(struct md_interception_stacks *);
uintptr_t md_stacks_take(struct md_interception_stacks *);
int md_stacks_add(struct md_interception_stacks *, uintptr_t);
void md_stacks_return(struct md_interception_stacks *, uintptr_t);
#endif
