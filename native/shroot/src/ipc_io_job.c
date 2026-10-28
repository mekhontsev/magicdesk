#define _GNU_SOURCE
#include "ipc_io_job.h"
#include "interruptible_call.h"
#include "event_signal.h"
#include <errno.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <unistd.h>

static void *run(void *opaque) {
    struct md_ipc_io_job *job=opaque;
    sigset_t mask; sigfillset(&mask); sigdelset(&mask,SIGUSR2);
    int error=pthread_sigmask(SIG_SETMASK,&mask,NULL);
    job->result=error ? -error : md_interruptible_syscall(job->number,
        job->args[0],job->args[1],job->args[2],job->args[3],job->args[4],&job->cancelled);
    if (md_event_signal(job->event)) abort();
    return NULL;
}
int md_ipc_io_job_start(struct md_ipc_io_job *job, long number, const long args[5]) {
    if (job->started) return -EBUSY;
    int prepared=md_interruptible_prepare();
    if (prepared) return prepared;
    job->event=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
    if (job->event<0) return -errno;
    job->number=number; memcpy(job->args,args,sizeof(job->args));
    atomic_init(&job->cancelled,0);
    int error=pthread_create(&job->thread,NULL,run,job);
    if (error) { close(job->event); job->event=-1; return -error; }
    job->started=1; return 0;
}
long md_ipc_io_job_finish(struct md_ipc_io_job *job, int interrupt) {
    if (!job->started) return -EINVAL;
    if (interrupt) {
        atomic_store_explicit(&job->cancelled,1,memory_order_release);
        int error=pthread_kill(job->thread,SIGUSR2);
        if (error && error!=ESRCH) abort();
    }
    /* EVENT_WAIT: native syscall completion, or cancellation acknowledged by
     * its signal/entry gate. Join precedes releasing buffers and FD custody. */
    int error=pthread_join(job->thread,NULL);
    if (error) abort();
    close(job->event); job->event=-1; job->started=0;
    return job->result;
}
int md_ipc_io_job_event(const struct md_ipc_io_job *job) {
    return job->started ? job->event : -1;
}
