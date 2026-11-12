#ifndef MD_SUPERVISOR_IDENTITY_H
#define MD_SUPERVISOR_IDENTITY_H

#include "supervisor_thread.h"

void identity_discard(struct thread *t);
void identity_buffer_control(struct thread *t,const struct supervisor_control_request *control);

int process_limit_call(struct thread *t,const struct seccomp_data *call,struct user_pt_regs *r);
int identity_call(struct thread *t,const struct seccomp_data *call,struct user_pt_regs *r, unsigned long cookie);

#endif
