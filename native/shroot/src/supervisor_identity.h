#ifndef MD_SUPERVISOR_IDENTITY_H
#define MD_SUPERVISOR_IDENTITY_H

#include "supervisor_thread.h"

void identity_copy_finish(struct thread *t, int error);

int process_limit_call(struct thread *t,struct user_pt_regs *r);
int identity_call(struct thread *t, struct user_pt_regs *r, unsigned long cookie);

int identity_checkpoint(struct thread *t,pid_t pid,uintptr_t checkpoint,struct user_pt_regs *regs);

#endif
