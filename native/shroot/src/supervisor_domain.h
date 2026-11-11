#ifndef MD_SUPERVISOR_DOMAIN_H
#define MD_SUPERVISOR_DOMAIN_H

#include "supervisor_thread.h"

void domain_path_finish(struct thread *t, int error);
void domain_buffer_control(struct thread *t,const struct user_pt_regs *r);
void setns_complete(struct thread *t,int error);
void setns_prepared(struct thread *t,long error);
int domain_call(struct thread *t, struct user_pt_regs *r, unsigned long cookie);
int domain_checkpoint(struct thread *t,uintptr_t checkpoint,struct user_pt_regs *regs);

#endif
