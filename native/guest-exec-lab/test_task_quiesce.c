#define _GNU_SOURCE
#include "task_quiesce.h"
#include <assert.h>
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <sys/ptrace.h>
#include <sys/wait.h>

static int interrupts, resumes, request;
static pid_t resumed, newborn;
static long trace(int op,pid_t pid,void *address,void *data) {
    (void)address;
    if (op==PTRACE_GETEVENTMSG) { *(unsigned long *)data=(unsigned long)newborn; return 0; }
    if (op==PTRACE_INTERRUPT) { ++interrupts; return 0; }
    assert(op==PTRACE_CONT || op==PTRACE_SYSCALL || op==PTRACE_SINGLESTEP);
    ++resumes; resumed=pid; request=op; return 0;
}
static int stop(int signal,unsigned event) { return (int)(event<<16)|(signal<<8)|0x7f; }
int main(void) {
    struct md_task_quiesce *q=md_task_quiesce_new(trace); assert(q);
    assert(!md_task_quiesce_add(q,1,0,PTRACE_SYSCALL));
    assert(!md_task_quiesce_add(q,2,1,PTRACE_CONT));
    assert(md_task_quiesce_waiting(q,1) && !md_task_quiesce_waiting(q,2));
    assert(!md_task_quiesce_interrupting(q,1));
    assert(!md_task_quiesce_stop(q) && interrupts==1 && !md_task_quiesce_ready(q));
    assert(md_task_quiesce_interrupting(q,1) && !md_task_quiesce_interrupting(q,2));
    assert(!md_task_quiesce_event(q,1,stop(SIGTRAP,PTRACE_EVENT_STOP),NULL));
    assert(!md_task_quiesce_waiting(q,1));
    assert(!md_task_quiesce_interrupting(q,1));
    assert(md_task_quiesce_ready(q));
    assert(!md_task_quiesce_release(q) && resumes==1 && resumed==1 && request==PTRACE_SYSCALL);
    int status; struct rusage usage;
    assert(!md_task_quiesce_take(q,&status,&usage));

    assert(!md_task_quiesce_add(q,3,0,PTRACE_CONT));
    assert(!md_task_quiesce_stop(q));
    struct rusage saved={.ru_maxrss=123};
    assert(!md_task_quiesce_event(q,3,stop(SIGUSR1,0),&saved));
    assert(md_task_quiesce_ready(q) && !md_task_quiesce_release(q) && resumes==1);
    /* A second repair can begin before the first repair's events are replayed. */
    int previous=interrupts;
    assert(!md_task_quiesce_add(q,3,0,PTRACE_CONT));
    assert(!md_task_quiesce_stop(q) && interrupts==previous && md_task_quiesce_ready(q));
    assert(!md_task_quiesce_release(q));
    assert(md_task_quiesce_take(q,&status,&usage)==3 && status==stop(SIGUSR1,0) && usage.ru_maxrss==123);

    for (int child_first=0;child_first<2;++child_first) {
        assert(!md_task_quiesce_add(q,4,0,PTRACE_SYSCALL));
        assert(!md_task_quiesce_stop(q)); newborn=5;
        if (child_first) assert(!md_task_quiesce_event(q,5,stop(SIGTRAP,PTRACE_EVENT_STOP),NULL));
        assert(!md_task_quiesce_event(q,4,stop(SIGTRAP,PTRACE_EVENT_CLONE),NULL));
        assert(md_task_quiesce_ready(q)==child_first);
        if (!child_first) assert(!md_task_quiesce_event(q,5,stop(SIGTRAP,PTRACE_EVENT_STOP),NULL));
        assert(md_task_quiesce_ready(q));
        assert(!md_task_quiesce_release(q));
        assert(md_task_quiesce_newborn(q,5)==1 && !md_task_quiesce_newborn(q,5));
        assert(md_task_quiesce_take(q,&status,NULL)==4 && status==stop(SIGTRAP,PTRACE_EVENT_CLONE));
        assert(!md_task_quiesce_take(q,&status,NULL));
    }
    assert(!md_task_quiesce_add(q,6,1,PTRACE_SYSCALL));
    assert(!md_task_quiesce_add(q,6,0,PTRACE_CONT));
    assert(!md_task_quiesce_stop(q));
    assert(!md_task_quiesce_event(q,6,stop(SIGTRAP,PTRACE_EVENT_STOP),NULL));
    assert(!md_task_quiesce_release(q) && resumed==6 && request==PTRACE_CONT);
    assert(!md_task_quiesce_add(q,7,0,0));
    previous=interrupts;
    assert(!md_task_quiesce_stop(q) && interrupts==previous);
    assert(!md_task_quiesce_event(q,7,stop(SIGTRAP,PTRACE_EVENT_STOP),NULL));
    assert(!md_task_quiesce_release(q));
    assert(md_task_quiesce_take(q,&status,NULL)==7);
    /* An existing syscall/debugger interrupt has the same kernel stop as
     * ours, but belongs to its original owner, across nested repairs too. */
    int old_resumes=resumes;
    previous=interrupts;
    assert(!md_task_quiesce_add(q,8,0,0));
    assert(!md_task_quiesce_stop(q) && interrupts==previous);
    assert(!md_task_quiesce_event(q,8,stop(SIGTRAP,PTRACE_EVENT_STOP),&saved));
    assert(md_task_quiesce_ready(q) && !md_task_quiesce_release(q));
    assert(resumes==old_resumes);
    assert(!md_task_quiesce_add(q,8,0,0));
    assert(!md_task_quiesce_stop(q) && md_task_quiesce_ready(q));
    assert(!md_task_quiesce_release(q) && resumes==old_resumes && interrupts==previous);
    assert(md_task_quiesce_take(q,&status,&usage)==8);
    assert(status==stop(SIGTRAP,PTRACE_EVENT_STOP) && usage.ru_maxrss==123);
    assert(!md_task_quiesce_take(q,&status,NULL));
    /* Cold task calls lend a continuation, not its event or resume ownership. */
    for (unsigned kind=MD_TASK_QUIESCE_HELD;kind<=MD_TASK_QUIESCE_EVENT;++kind) {
        int start=resumes, events=stop(SIGTRAP,PTRACE_EVENT_SECCOMP);
        struct md_task_quiesce_stop boundary;
        assert(!md_task_quiesce_add(q,9,kind==MD_TASK_QUIESCE_HELD,PTRACE_CONT));
        assert(!md_task_quiesce_stop(q));
        if (kind!=MD_TASK_QUIESCE_HELD) {
            assert(md_task_quiesce_borrow(q,9,&boundary)==-EBUSY);
            assert(!md_task_quiesce_event(q,9,kind==MD_TASK_QUIESCE_EVENT
                ? events : stop(SIGTRAP,PTRACE_EVENT_STOP),&saved));
        }
        assert(md_task_quiesce_ready(q) && !md_task_quiesce_borrow(q,9,&boundary));
        assert(!md_task_quiesce_waiting(q,9) && !md_task_quiesce_interrupting(q,9));
        assert(boundary.kind==kind && boundary.status==(kind==MD_TASK_QUIESCE_EVENT ? events : 0));
        assert(!md_task_quiesce_ready(q) && md_task_quiesce_release(q)==-EBUSY);
        assert(md_task_quiesce_borrow(q,9,&boundary)==-EBUSY);
        assert(md_task_quiesce_add(q,9,1,0)==-EBUSY);
        assert(md_task_quiesce_event(q,9,events,NULL)==-EBUSY);
        assert(md_task_quiesce_newborn(q,9)==-EBUSY && !md_task_quiesce_take(q,&status,NULL));
        assert(!md_task_quiesce_return(q,9) && md_task_quiesce_return(q,9)==-EINVAL);
        assert(md_task_quiesce_ready(q) && !md_task_quiesce_release(q));
        assert(resumes==start+(kind==MD_TASK_QUIESCE_SYNTHETIC));
        if (kind==MD_TASK_QUIESCE_EVENT)
            assert(md_task_quiesce_take(q,&status,&usage)==9 && status==events && usage.ru_maxrss==123);
        assert(!md_task_quiesce_take(q,&status,NULL));
    }
    assert(!md_task_quiesce_add(q,9,0,PTRACE_CONT) && !md_task_quiesce_stop(q));
    assert(!md_task_quiesce_event(q,9,0,NULL));
    struct md_task_quiesce_stop boundary;
    assert(md_task_quiesce_borrow(q,9,&boundary)==-ESRCH);
    assert(!md_task_quiesce_release(q) && md_task_quiesce_take(q,&status,NULL)==9 && status==0);
    for (unsigned round=0;round<3;++round) {
        enum { COUNT=4096 };
        int start=resumes;
        for (int i=0;i<COUNT;++i) assert(!md_task_quiesce_add(q,100+i,0,PTRACE_CONT));
        assert(!md_task_quiesce_stop(q) && !md_task_quiesce_ready(q));
        for (int i=0;i<COUNT;++i) {
            assert(!md_task_quiesce_event(q,100+i,stop(SIGTRAP,PTRACE_EVENT_STOP),NULL));
            assert(md_task_quiesce_ready(q)==(i==COUNT-1));
        }
        assert(!md_task_quiesce_release(q) && resumes==start+COUNT);
        assert(!md_task_quiesce_take(q,&status,NULL));
    }
    md_task_quiesce_destroy(q);
    puts("PASS task quiescence: exact resume, deferred signals/rusage, nested replay, both birth orders and private helper ownership");
    return 0;
}
