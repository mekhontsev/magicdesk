#ifndef MD_THREAD_CONTEXT_H
#define MD_THREAD_CONTEXT_H
#include <ucontext.h>
int md_thread_initialize(void);
long md_thread_clone(unsigned long *arguments, ucontext_t *context);
long md_thread_altstack(unsigned long *arguments);
__attribute__((noreturn)) void md_thread_exit(int status, ucontext_t *context);
void md_clone_child(void *frame);
long md_clone_resume(unsigned long flags, void *frame, void *parent_tid, void *tls, void *child_tid);
extern void md_clone_return(void);
__attribute__((noreturn)) void md_restore_frame(void *frame);
__attribute__((noreturn)) void md_unmap_exit(void *mapping, unsigned long size, int status);
#endif
