#define _GNU_SOURCE
#include "task_wait.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/wait.h>

struct call { pid_t selected, result; int error; };
static struct call calls[40];
static unsigned queued, taken;
static struct rusage *expected_usage;
static int batch_usage;
static struct {
    unsigned population, queries;
    unsigned char ready[4096];
} model;
static void expect(pid_t selected, pid_t result, int error) {
    assert(queued < sizeof(calls)/sizeof(*calls));
    calls[queued++] = (struct call){selected, result, error};
}
pid_t __wrap_wait4(pid_t selected, int *status, int options, struct rusage *usage) {
    if (model.population) {
        assert(options==(__WALL|WNOHANG) && !usage);
        ++model.queries;
        unsigned first=selected==-1 ? 0 : (unsigned)(selected-10000);
        unsigned limit=selected==-1 ? model.population : first+1;
        assert(first<model.population && limit<=model.population);
        for (unsigned i=first;i<limit;++i) if (model.ready[i]) {
            model.ready[i]=0; *status=0x7057f;
            return (pid_t)(10000+i);
        }
        return 0;
    }
    assert(taken < queued);
    struct call call = calls[taken++];
    if (call.selected!=selected)
        fprintf(stderr,"wait query %u/%u: expected pid=%d, got pid=%d\n",taken,queued,call.selected,selected);
    assert(call.selected == selected && options == (__WALL | WNOHANG));
    assert(batch_usage ? usage!=NULL : usage == expected_usage);
    if (call.result > 0) {
        *status = 0x7057f;
        if (usage) usage->ru_utime.tv_usec = 1234;
    }
    errno = call.error;
    return call.result;
}
static void done(void) { assert(taken == queued); taken = queued = 0; }

static void fair_discovery(unsigned population,unsigned hot) {
    struct md_task_wait w={0};
    struct md_task_wait_member *members=calloc(population,sizeof(*members));
    assert(members && population<=sizeof(model.ready) && hot<population);
    model.population=population; model.queries=0;
    for (unsigned i=0;i<population;++i) {
        md_task_wait_track(&w,&members[i],(pid_t)(10000+i));
        model.ready[i]=i<hot || i==population-1;
    }
    /* Only the first hot task ever supplies a hint. General waits start at
     * the same hot prefix, including one larger than the pending batch. */
    unsigned events=0;
    for (;;) {
        md_task_wait_hint(&w,10000);
        int status;
        pid_t pid=md_task_wait_ready(&w,&status,NULL);
        assert(pid>=10000 && (unsigned)(pid-10000)<population && status==0x7057f);
        ++events;
        assert(events<=2*population+64);
        if ((unsigned)(pid-10000)==population-1) break;
        assert((unsigned)(pid-10000)<hot);
        model.ready[pid-10000]=1;
    }
    /* Sparse populations must not pay two idle probes per returned event. */
    if (hot==1) assert(model.queries<=2*events+64);
    for (unsigned i=0;i<population;++i) md_task_wait_untrack(&w,&members[i]);
    free(members); model.population=0;
}

int main(void) {
    int status;
    struct md_task_wait w = {0};
    expect(-1,10,0);
    assert(md_task_wait_ready(&w,&status,NULL)==10 && status==0x7057f);
    done();
    struct rusage usage = {0};
    expected_usage = &usage;
    md_task_wait_hint(&w,11);
    expect(11,11,0);
    assert(md_task_wait_ready(&w,&status,&usage)==11 && usage.ru_utime.tv_usec==1234);
    done(); expected_usage=NULL;

    /* Keep distinct notifications and deduplicate without buffering statuses. */
    md_task_wait_hint(&w,11); md_task_wait_hint(&w,11); md_task_wait_hint(&w,12);
    expect(11,0,0); expect(12,12,0);
    assert(md_task_wait_ready(&w,&status,NULL)==12);
    done();
    expect(12,0,0); expect(10,0,0); expect(-1,13,0);
    assert(md_task_wait_ready(&w,&status,NULL)==13);
    done();
    w=(struct md_task_wait){0};
    md_task_wait_hint(&w,11); md_task_wait_hint(&w,12);
    expect(11,-1,ECHILD); expect(12,-1,ESRCH); expect(-1,14,0);
    assert(md_task_wait_ready(&w,&status,NULL)==14);
    done();

    w=(struct md_task_wait){0};
    md_task_wait_hint(&w,15);
    expect(15,-1,EINTR);
    assert(md_task_wait_ready(&w,&status,NULL)==-1 && errno==EINTR);
    done();
    expect(-1,15,0);
    assert(md_task_wait_ready(&w,&status,NULL)==15);
    done();
    expect(15,-1,ECHILD); expect(-1,-1,ECHILD);
    assert(md_task_wait_ready(&w,&status,NULL)==-1 && errno==ECHILD);
    done();

    /* An older unnotified stop gets service despite a perpetually hot TID. */
    w=(struct md_task_wait){0};
    for (unsigned i=0;i<8;i++) {
        md_task_wait_hint(&w,16);
        expect(16,16,0);
        assert(md_task_wait_ready(&w,&status,NULL)==16); done();
    }
    md_task_wait_hint(&w,16);
    expect(-1,17,0);
    assert(md_task_wait_ready(&w,&status,NULL)==17); done();
    expect(16,16,0);
    assert(md_task_wait_ready(&w,&status,NULL)==16); done();

    /* Overflow and stale hints remain bounded; a general wait finds the rest. */
    w=(struct md_task_wait){0};
    for (pid_t pid=20;pid<40;pid++) md_task_wait_hint(&w,pid);
    for (pid_t pid=20;pid<28;pid++) expect(pid,0,0);
    expect(-1,39,0);
    assert(md_task_wait_ready(&w,&status,NULL)==39); done();
    expect(39,0,0); expect(-1,0,0);
    assert(!md_task_wait_ready(&w,&status,NULL)); done();
    for (unsigned i=0;i<1024;i++) {
        md_task_wait_hint(&w,i&1 ? 0 : -1);
        assert(!md_task_wait_ready(&w,&status,NULL));
    }
    done();
    md_task_wait_hint(&w,18);
    expect(18,0,0); expect(-1,19,0);
    assert(md_task_wait_ready(&w,&status,NULL)==19); done();
    expect(19,0,0); expect(-1,0,0);
    assert(!md_task_wait_ready(&w,&status,NULL)); done();
    md_task_wait_hint(&w,20);
    expect(20,20,0);
    assert(md_task_wait_ready(&w,&status,NULL)==20); done();
    /* Wrap the hint ring while preserving order and the global budget. */
    w=(struct md_task_wait){0};
    for (pid_t pid=30;pid<38;pid++) md_task_wait_hint(&w,pid);
    for (pid_t pid=30;pid<33;pid++) {
        expect(pid,pid,0);
        assert(md_task_wait_ready(&w,&status,NULL)==pid); done();
    }
    for (pid_t pid=38;pid<41;pid++) md_task_wait_hint(&w,pid);
    for (pid_t pid=33;pid<38;pid++) {
        expect(pid,pid,0);
        assert(md_task_wait_ready(&w,&status,NULL)==pid); done();
    }
    expect(-1,50,0);
    assert(md_task_wait_ready(&w,&status,NULL)==50); done();
    for (pid_t pid=38;pid<41;pid++) {
        expect(pid,pid,0);
        assert(md_task_wait_ready(&w,&status,NULL)==pid); done();
    }
    /* The previous TID is a bounded preference, not a substitute for the
     * general queue or a claim that it has already stopped again. */
    w=(struct md_task_wait){0};
    expect(-1,60,0);
    assert(md_task_wait_ready(&w,&status,NULL)==60); done();
    for (unsigned i=0;i<8;++i) {
        expect(60,60,0);
        assert(md_task_wait_ready(&w,&status,NULL)==60); done();
    }
    expect(-1,61,0);
    assert(md_task_wait_ready(&w,&status,NULL)==61); done();
    md_task_wait_hint(&w,62);
    expect(62,62,0);
    assert(md_task_wait_ready(&w,&status,NULL)==62); done();
    expect(62,0,0); expect(61,0,0); expect(60,0,0); expect(-1,0,0);
    assert(!md_task_wait_ready(&w,&status,NULL) && !w.recent[0]); done();
    md_task_wait_hint(&w,63);
    expect(63,63,0);
    assert(md_task_wait_ready(&w,&status,NULL)==63); done();
    expect(63,-1,EINTR);
    assert(md_task_wait_ready(&w,&status,NULL)==-1 && errno==EINTR); done();
    expect(63,0,0); expect(-1,64,0);
    assert(md_task_wait_ready(&w,&status,NULL)==64); done();
    /* Alternating checkpoint owners do not require a tree-wide wait. Misses
     * consume the same budget, so speculation cannot postpone collection. */
    w=(struct md_task_wait){0};
    for (pid_t pid=70;pid<74;++pid) {
        md_task_wait_hint(&w,pid); expect(pid,pid,0);
        assert(md_task_wait_ready(&w,&status,NULL)==pid); done();
    }
    expect(73,0,0); expect(72,72,0);
    assert(md_task_wait_ready(&w,&status,NULL)==72); done();
    expect(72,0,0); expect(73,73,0);
    assert(md_task_wait_ready(&w,&status,NULL)==73); done();
    expect(-1,80,0);
    assert(md_task_wait_ready(&w,&status,NULL)==80); done();
    expect(80,0,0); expect(73,0,0); expect(72,0,0); expect(71,71,0);
    assert(md_task_wait_ready(&w,&status,NULL)==71); done();
    expect(71,0,0); expect(80,0,0); expect(73,0,0); expect(72,0,0); expect(-1,0,0);
    assert(!md_task_wait_ready(&w,&status,NULL)); done();
    /* A small tree collects before any hot child can repeat its checkpoint.
     * An unnotified older stop survives membership changes and a new hint. */
    w=(struct md_task_wait){0};
    struct md_task_wait_member members[3]={0};
    for (unsigned i=0;i<3;++i) md_task_wait_track(&w,&members[i],90+i);
    expect(-1,90,0); expect(-1,92,0); expect(-1,0,0);
    assert(md_task_wait_ready(&w,&status,NULL)==90); done();
    md_task_wait_hint(&w,90);
    assert(md_task_wait_ready(&w,&status,NULL)==92); done();
    assert(w.cursor==&members[0]);
    md_task_wait_untrack(&w,&members[0]);
    assert(w.cursor==&members[1] && members[2].next==&members[1]);
    expect(-1,91,0); expect(-1,0,0);
    assert(md_task_wait_ready(&w,&status,NULL)==91); done();
    md_task_wait_untrack(&w,&members[2]);
    md_task_wait_untrack(&w,&members[2]);
    assert(w.cursor==&members[1] && members[1].next==&members[1]);
    /* One live tracee does not acquire an extra fairness syscall. */
    md_task_wait_hint(&w,91);
    expect(91,91,0);
    assert(md_task_wait_ready(&w,&status,NULL)==91); done();
    md_task_wait_untrack(&w,&members[1]);
    assert(!w.cursor);
    /* Larger trees still impose a finite discovery bound on a hot hint. */
    const unsigned populations[]={6,64,65,388,1024,4096};
    for (unsigned n=0;n<sizeof(populations)/sizeof(*populations);++n) {
        w=(struct md_task_wait){.members=populations[n]};
        unsigned budget=populations[n]<64 ? 64 : populations[n]>1024 ? 1024 : populations[n];
        for (unsigned i=0;i<budget;++i) {
            md_task_wait_hint(&w,99); expect(99,99,0);
            assert(md_task_wait_ready(&w,&status,NULL)==99); done();
        }
        md_task_wait_hint(&w,99);
        expect(-1,98,0); expect(-1,0,0);
        assert(md_task_wait_ready(&w,&status,NULL)==98); done();
    }
    /* Ready children are collected while none can yet repeat a checkpoint.
     * Buffered statuses survive SIGCHLD hints, membership and accounting changes. */
    w=(struct md_task_wait){0};
    struct md_task_wait_member batch[6]={0};
    for (unsigned i=0;i<6;++i) md_task_wait_track(&w,&batch[i],100+i);
    batch_usage=1;
    /* Force discovery after the directed budget, not on every stop. */
    w.directed=64;
    expect(-1,100,0); expect(-1,101,0); expect(-1,102,0); expect(-1,0,0);
    assert(md_task_wait_ready(&w,&status,&usage)==100 && status==0x7057f && usage.ru_utime.tv_usec==1234);
    done(); batch_usage=0;
    md_task_wait_hint(&w,105);
    md_task_wait_untrack(&w,&batch[0]); md_task_wait_untrack(&w,&batch[1]);
    assert(md_task_wait_take(&w,&status,NULL)==101 && status==0x7057f);
    usage=(struct rusage){0};
    assert(md_task_wait_ready(&w,&status,&usage)==102 && usage.ru_utime.tv_usec==1234);
    done();
    for (unsigned i=2;i<6;++i) md_task_wait_untrack(&w,&batch[i]);
    assert(!w.members && !w.pending_count);
    /* Cancellation drains collected stops without entering wait4, including
     * untracked newborns and terminal stops that still require CONT. */
    for (unsigned i=0;i<100;++i) assert(!md_task_wait_take(&w,&status,NULL));
    done();
    w=(struct md_task_wait){.members=6};
    expect(-1,110,0); expect(-1,-1,EINTR);
    assert(md_task_wait_ready(&w,&status,NULL)==110); done();
    expect(110,0,0);
    expect(-1,-1,EINTR);
    assert(md_task_wait_ready(&w,&status,NULL)==-1 && errno==EINTR); done();
    expect(110,0,0);
    for (unsigned i=0;i<32;++i) expect(-1,120+i,0);
    assert(md_task_wait_ready(&w,&status,NULL)==120); done();
    for (unsigned i=1;i<32;++i) {
        usage=(struct rusage){.ru_utime.tv_usec=999};
        assert(md_task_wait_ready(&w,&status,&usage)==120+(pid_t)i && !usage.ru_utime.tv_usec);
    }
    done();
    for (pid_t pid=151;pid>=148;--pid) expect(pid,0,0);
    expect(-1,0,0);
    assert(!md_task_wait_ready(&w,&status,NULL) && w.drained); done();
    for (unsigned i=0;i<1000;++i) assert(!md_task_wait_ready(&w,&status,NULL));
    done();
    /* Each TID gets one query per discovery pass even if it appears in every
     * source. Only the general wait establishes that the queue is drained. */
    w=(struct md_task_wait){0};
    struct md_task_wait_member duplicate[6]={0};
    for (unsigned i=0;i<6;++i) md_task_wait_track(&w,&duplicate[i],200+i);
    w.recent[0]=200; w.recent[1]=201; w.recent[2]=202;
    md_task_wait_hint(&w,200); md_task_wait_hint(&w,201);
    expect(200,0,0); expect(201,0,0); expect(202,0,0); expect(-1,0,0);
    assert(!md_task_wait_ready(&w,&status,NULL) && w.drained); done();
    assert(w.cursor==&duplicate[1]);
    /* A later signal begins a new pass; a previous miss is never cached. */
    md_task_wait_hint(&w,200);
    expect(201,0,0); expect(200,200,0);
    assert(md_task_wait_ready(&w,&status,NULL)==200); done();
    for (unsigned i=0;i<6;++i) md_task_wait_untrack(&w,&duplicate[i]);
    for (unsigned i=0;i<sizeof(populations)/sizeof(*populations);++i) {
        fair_discovery(populations[i],1);
        fair_discovery(populations[i],populations[i]>48 ? 48 : populations[i]-1);
    }
    for (unsigned population=5;population<=128;++population)
        for (unsigned hot=1;hot<population;hot=hot*2+1) fair_discovery(population,hot);
    puts("PASS task event selection");
    return 0;
}
