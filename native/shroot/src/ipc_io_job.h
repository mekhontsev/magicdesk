#ifndef MD_IPC_IO_JOB_H
#define MD_IPC_IO_JOB_H
#include <pthread.h>
#include <stdatomic.h>

/* Host-owned native syscall, not a guest helper and not a file-table owner. */
struct md_ipc_io_job {
    pthread_t thread;
    int event, started;
    atomic_int cancelled, completed;
    long number, args[5], result;
};
int md_ipc_io_job_start(struct md_ipc_io_job *, long, const long[5]);
/* A service wake is not proof of completion. Collect never waits for IO:
 * zero retains the pending job, one publishes its result and retires it. */
int md_ipc_io_job_collect(struct md_ipc_io_job *, long *);
long md_ipc_io_job_cancel(struct md_ipc_io_job *);
int md_ipc_io_job_event(const struct md_ipc_io_job *);
#endif
