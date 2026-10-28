#define _GNU_SOURCE
#include "file_open_job.h"
#include "interruptible_call.h"
#include "fs_wire.h"
#include "raw.h"
#include "directory_record.h"
#include <errno.h>
#include <limits.h>
#include <signal.h>
#include <pthread.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <unistd.h>

struct open_result { int error; struct md_open_observation observation; };

static int isolate_proc(const unsigned *keep, unsigned count) {
    long directory=RAW4(openat,AT_FDCWD,"/proc/self/fd",O_RDONLY|O_DIRECTORY|O_CLOEXEC,0);
    if (directory<0) return (int)directory;
    char bytes[4096];
    int error=0;
    for (;;) {
        long size=RAW3(getdents64,directory,bytes,sizeof(bytes));
        if (size<=0) { error=(int)size; break; }
        for (long at=0;at<size;) {
            struct md_directory_entry e;
            if (!md_directory_record_read(bytes+at,(size_t)(size-at),&e)) { error=-EIO; break; }
            const char *name=bytes+at+offsetof(struct md_directory_entry,name);
            if (name[0]>='0' && name[0]<='9') {
                unsigned number=0;
                size_t n=0;
                while (name[n]>='0' && name[n]<='9') {
                    if (number>((unsigned)INT_MAX-9)/10) { error=-EIO; break; }
                    number=number*10+(unsigned)(name[n++]-'0');
                }
                if (error || name[n]) { error=-EIO; break; }
                int retained=number==(unsigned)directory;
                for (unsigned i=0;i<count;++i) retained|=number==keep[i];
                if (!retained) RAW1(close,number);
            }
            at+=e.size;
        }
        if (error) break;
    }
    RAW1(close,directory); return error;
}
static int isolate(int pin, int control, int channel) {
    unsigned keep[3]={(unsigned)pin,(unsigned)channel,(unsigned)control};
    unsigned count=control>=0 ? 3 : 2;
    for (unsigned i=0;i<count;++i) for (unsigned j=i+1;j<count;++j)
        if (keep[j]<keep[i]) { unsigned n=keep[i]; keep[i]=keep[j]; keep[j]=n; }
    unsigned from=0;
    for (unsigned i=0;i<=count;++i) {
        unsigned to=i<count ? keep[i] : UINT_MAX;
        if (from<to || i==count) {
            long r=RAW3(close_range,from,i<count ? to-1 : to,0);
            if (r==-ENOSYS) return isolate_proc(keep,count);
            if (r<0) return (int)r;
        }
        from=to+1;
    }
    return 0;
}
int md_open_job_start(struct md_open_job *job, int pin, int flags, struct md_open_completion completion) {
    if (!job || job->pid || pin<0 || (completion.kind!=MD_OPEN_PIPE && completion.kind!=MD_OPEN_FIFO)
            || (completion.kind==MD_OPEN_PIPE && completion.control<0)) return -EINVAL;
    int prepared=md_interruptible_prepare();
    if (prepared) return prepared;
    atomic_int *interrupted=mmap(NULL,sizeof(*interrupted),PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    if (interrupted==MAP_FAILED) return -errno;
    atomic_init(interrupted,0);
    int pair[2];
    if (socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC|SOCK_NONBLOCK,0,pair)) {
        int error=-errno; munmap(interrupted,sizeof(*interrupted)); return error;
    }
    sigset_t mask,previous;
    sigemptyset(&mask); sigaddset(&mask,SIGUSR2);
    int blocked=pthread_sigmask(SIG_BLOCK,&mask,&previous);
    if (blocked) { close(pair[0]); close(pair[1]); munmap(interrupted,sizeof(*interrupted)); return -blocked; }
    pid_t parent=getpid(), child=fork();
    if (!child) {
        /* After fork from a multithreaded supervisor, only raw syscalls and
         * stack-only helpers run; no allocator, SQLite, pthread or logging. */
        long r=RAW2(prctl,PR_SET_PDEATHSIG,SIGKILL);
        if (!r && RAW0(getppid)!=parent) {
            /* The owner died before death-signal admission. Do not publish to
             * the receive endpoint still inherited by this unisolated child. */
            RAW1(exit_group,125); __builtin_unreachable();
        }
        if (!r) r=isolate(pin,completion.kind==MD_OPEN_PIPE ? completion.control : -1,pair[1]);
        uint64_t mask=~(1ULL<<(SIGUSR2-1));
        if (!r) r=RAW4(rt_sigprocmask,SIG_SETMASK,&mask,0,8);
        struct open_result result={0};
        if (!r) r=md_complete_open_interruptible(pin,flags|O_CLOEXEC,completion,&result.observation,interrupted);
        struct md_fs_rights rights={0};
        result.error=r<0 ? (int)r : 0;
        if (!result.error) { rights.count=1; rights.fd[0]=(int)r; }
        long sent=md_fs_send(pair[1],&result,sizeof(result),&rights);
        RAW1(exit_group,sent==sizeof(result) ? 0 : 125);
        __builtin_unreachable();
    }
    int error=child<0 ? -errno : 0;
    int restored=pthread_sigmask(SIG_SETMASK,&previous,NULL);
    if (restored) abort();
    close(pair[1]);
    if (error) { close(pair[0]); munmap(interrupted,sizeof(*interrupted)); }
    else *job=(struct md_open_job){.pid=child,.channel=pair[0],.interrupted=interrupted};
    return error;
}
int md_open_job_interrupt(struct md_open_job *job) {
    if (!job || !job->pid || job->cancelled) return -EINVAL;
    if (atomic_exchange_explicit(job->interrupted,1,memory_order_acq_rel)) return 0;
    return kill(job->pid,SIGUSR2) && errno!=ESRCH ? -errno : 0;
}
int md_open_job_cancel(struct md_open_job *job) {
    if (!job || !job->pid || job->cancelled) return 0;
    if (kill(job->pid,SIGKILL) && errno!=ESRCH) return -errno;
    job->cancelled=1;
    close(job->channel); job->channel=-1;
    return 0;
}
int md_open_job_finish(struct md_open_job *job, int status,struct md_open_observation *observation) {
    if (!job || !job->pid || (!WIFEXITED(status) && !WIFSIGNALED(status))) return -EINVAL;
    int result=-ECANCELED;
    if (!job->cancelled) {
        struct md_fs_rights rights={0};
        struct open_result reply={0};
        long received=md_fs_receive(job->channel,&reply,sizeof(reply),&rights);
        if (!WIFEXITED(status) || WEXITSTATUS(status)) result=-EIO;
        else if (received!=sizeof(reply) || reply.error>0 || reply.error < -4095
                || reply.observation.initial_reader>1
                || rights.count!=(unsigned)!reply.error) result=-EPROTO;
        else if (reply.error) result=reply.error;
        else { result=rights.fd[0]; rights.count=0; if (observation) *observation=reply.observation; }
        md_fs_close_rights(&rights); close(job->channel);
    }
    munmap(job->interrupted,sizeof(*job->interrupted));
    *job=(struct md_open_job){0}; return result;
}
