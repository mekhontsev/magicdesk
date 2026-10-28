#ifndef MD_FILE_OPEN_JOB_H
#define MD_FILE_OPEN_JOB_H
#include "file_open.h"
#include <sys/types.h>

/* A deferred open owns a separate kernel file table, never a guest table.
 * The caller reaps this exact PID through its existing SIGCHLD event loop. */
struct md_open_job { pid_t pid; int channel, cancelled; atomic_int *interrupted; };
int md_open_job_start(struct md_open_job *, int pin, int flags, struct md_open_completion);
/* Request interruption without discarding an already admitted endpoint. */
int md_open_job_interrupt(struct md_open_job *);
/* SIGKILL cancels even native FIFO open/flock. Keep the PID until reap. */
int md_open_job_cancel(struct md_open_job *);
/* Consume an observed terminal wait status, transferring one result FD or errno. */
int md_open_job_finish(struct md_open_job *, int status, struct md_open_observation *);
#endif
