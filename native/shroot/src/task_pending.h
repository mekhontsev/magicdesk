#ifndef MD_TASK_PENDING_H
#define MD_TASK_PENDING_H
#include <errno.h>
#include <limits.h>
#include <linux/ptrace.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <sys/types.h>

/* Read-only inspection of a stopped task's explicitly selected pending queue. A positive visitor
 * result stops the scan; zero continues. Linux walks the queue from its head
 * for each PEEKSIGINFO offset, so grow batches geometrically, not quadratically
 * many fixed-size walks. Ordinary short queues need no allocation. */
static inline int md_task_pending_scan_scope(long (*trace)(int,pid_t,void *,void *),pid_t pid,
        unsigned flags,int (*visit)(void *,const siginfo_t *),void *context) {
    siginfo_t local[16],*heap=NULL,*items=local;
    struct ptrace_peeksiginfo_args args={.nr=sizeof(local)/sizeof(*local),.flags=flags};
    int result=0;
    for (;;) {
        long count=trace(PTRACE_PEEKSIGINFO,pid,&args,items);
        if (count<0) { result=-errno; break; }
        if (count>args.nr) { result=-EPROTO; break; }
        for (long i=0;i<count;++i) if ((result=visit(context,&items[i]))) break;
        if (result || count<args.nr) break;
        if (args.nr>INT_MAX/2 || (size_t)args.nr>SIZE_MAX/2/sizeof(*items)
                || args.off>UINT64_MAX-(uint64_t)count) { result=-EOVERFLOW; break; }
        args.off+=(uint64_t)count; args.nr*=2;
        siginfo_t *next=realloc(heap,(size_t)args.nr*sizeof(*items));
        if (!next) { result=-ENOMEM; break; }
        heap=items=next;
    }
    free(heap);
    return result;
}
static inline int md_task_pending_scan(long (*trace)(int,pid_t,void *,void *),pid_t pid,
        int (*visit)(void *,const siginfo_t *),void *context) {
    return md_task_pending_scan_scope(trace,pid,0,visit,context);
}
#endif
