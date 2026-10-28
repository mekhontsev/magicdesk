#ifndef MD_INTERRUPTIBLE_CALL_H
#define MD_INTERRUPTIBLE_CALL_H
#include <stdatomic.h>

/* Host helper only. The entry gate closes the cancellation-check/SVC race;
 * a completed syscall always retains its result and descriptor custody. */
int md_interruptible_prepare(void);
long md_interruptible_syscall(long, long, long, long, long, long, const atomic_int *);
#endif
