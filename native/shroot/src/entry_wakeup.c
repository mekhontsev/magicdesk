#define _GNU_SOURCE
#include "entry_wakeup.h"
#include "process_owner.h"
#include <errno.h>
#include <sys/signalfd.h>

int md_entry_wakeup_open(void) {
    uint64_t mask=1ULL<<(MD_ENTRY_WAKE_SIGNAL-1),previous;
    long error=RAW4(rt_sigprocmask,SIG_BLOCK,&mask,&previous,8);
    if (error<0) return (int)error;
    struct md_kernel_action action={0},old;
    error=RAW4(rt_sigaction,MD_ENTRY_WAKE_SIGNAL,&action,&old,8);
    if (error>=0) {
        error=RAW4(signalfd4,-1,&mask,8,SFD_CLOEXEC|SFD_NONBLOCK);
        if (error>=0) return (int)error;
        RAW4(rt_sigaction,MD_ENTRY_WAKE_SIGNAL,&old,0,8);
    }
    RAW4(rt_sigprocmask,SIG_SETMASK,&previous,0,8);
    return (int)error;
}
int md_entry_wakeup_read(int fd,int *callers,unsigned capacity) {
    struct signalfd_siginfo records[32];
    if (!capacity) return -EINVAL;
    if (capacity>32) capacity=32;
    long bytes;
    do { bytes=RAW3(read,fd,records,capacity*sizeof(*records)); } while (bytes==-EINTR);
    if (bytes==-EAGAIN) return 0;
    if (bytes<0) return (int)bytes;
    if (!bytes || bytes%(long)sizeof(*records)) return -EIO;
    unsigned count=0;
    for (unsigned i=0;i<(unsigned long)bytes/sizeof(*records);++i)
        if (records[i].ssi_signo==MD_ENTRY_WAKE_SIGNAL && (int)records[i].ssi_code==SI_QUEUE
                && records[i].ssi_int==MD_ENTRY_WAKE_CODE && records[i].ssi_pid)
            callers[count++]=(int)records[i].ssi_pid;
    return (int)count;
}
