#define _GNU_SOURCE
#include "robust_futex.h"
#include "task_child_reap.h"
#include "task_checkpoint.h"
#include "task_quiesce.h"
#include "task_retained.h"
#include "task_robust.h"
#include <asm/ptrace.h>
#include <assert.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/futex.h>
#include <linux/seccomp.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/signalfd.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

struct node { uint32_t word, padding; uint64_t next; };
static const uint32_t owner=137;
struct execution { unsigned reads, exchanges, wakes; int race; uintptr_t fault; };
static void run(struct md_robust_head *head,struct execution *e) {
    struct md_robust_cleanup c; md_robust_begin(&c,(uintptr_t)head,owner);
    while (c.request.operation) {
        struct md_robust_request r=c.request;
        uint64_t data[3]={0}; int error=0;
        switch (r.operation) {
        case MD_ROBUST_READ:
            ++e->reads;
            if (r.address==e->fault) error=-EFAULT;
            else memcpy(data,(void *)r.address,r.size);
            break;
        case MD_ROBUST_CMPXCHG: {
            ++e->exchanges;
            uint32_t *p=(void *)r.address;
            if (e->race) { *p=e->race; e->race=0; }
            uint32_t observed=r.expected;
            __atomic_compare_exchange_n(p,&observed,r.replacement,0,__ATOMIC_SEQ_CST,__ATOMIC_SEQ_CST);
            memcpy(data,&observed,sizeof(observed));
            break;
        }
        case MD_ROBUST_WAKE: ++e->wakes; break;
        default: assert(0);
        }
        md_robust_complete(&c,error,data);
    }
}
static struct md_robust_head head_for(struct node *node) {
    return (struct md_robust_head){.next=(uintptr_t)&node->next,
        .offset=(uint64_t)-(int64_t)offsetof(struct node,next)};
}
static void ordinary(void) {
    struct node a={.word=owner|FUTEX_WAITERS},b={.word=owner+1};
    struct md_robust_head h=head_for(&a);
    a.next=(uintptr_t)&b.next; b.next=(uintptr_t)&h;
    struct execution e={0}; run(&h,&e);
    assert(a.word==(FUTEX_WAITERS|FUTEX_OWNER_DIED) && b.word==owner+1);
    assert(e.exchanges==1 && e.wakes==1);
    e=(struct execution){0}; run(NULL,&e); assert(!e.reads);
    h.next=(uintptr_t)&h; run(&h,&e); assert(e.reads==1);
}
static void races(void) {
    struct node n={.word=owner}; struct md_robust_head h=head_for(&n);
    n.next=(uintptr_t)&h;
    struct execution e={.race=owner+1}; run(&h,&e);
    assert(n.word==owner+1 && e.exchanges==1 && !e.wakes);
    n.word=owner; e=(struct execution){.race=owner|FUTEX_WAITERS}; run(&h,&e);
    assert(n.word==(FUTEX_OWNER_DIED|FUTEX_WAITERS) && e.exchanges==2 && e.wakes==1);
    n.word=owner|FUTEX_OWNER_DIED|FUTEX_WAITERS; e=(struct execution){0}; run(&h,&e);
    assert(n.word==(FUTEX_OWNER_DIED|FUTEX_WAITERS) && e.wakes==1);
}
static void incomplete(void) {
    struct node n={0}; struct md_robust_head h=head_for(&n);
    h.pending=h.next; n.next=(uintptr_t)&h;
    struct execution e={0}; run(&h,&e);
    assert(!n.word && e.wakes==1 && !e.exchanges);
    n.word=owner|FUTEX_WAITERS; e=(struct execution){0}; run(&h,&e);
    assert(e.wakes==1 && e.exchanges==1);
    h.next=(uintptr_t)&h; n.word=owner; e=(struct execution){0}; run(&h,&e);
    assert(n.word==FUTEX_OWNER_DIED && !e.wakes && e.exchanges==1);
    h.pending|=1; n.word=owner|FUTEX_WAITERS; e=(struct execution){0}; run(&h,&e);
    assert(n.word==(FUTEX_OWNER_DIED|FUTEX_WAITERS) && !e.wakes && e.exchanges==1);
}
static void malformed(void) {
    struct node n={.word=owner|FUTEX_WAITERS}; struct md_robust_head h=head_for(&n);
    n.next=(uintptr_t)&n.next;
    struct execution e={0}; run(&h,&e);
    assert(e.reads==1+2*ROBUST_LIST_LIMIT && e.exchanges==1 && e.wakes==1);
    n.word=owner; e=(struct execution){.fault=(uintptr_t)&n.next}; run(&h,&e);
    assert(n.word==FUTEX_OWNER_DIED && e.reads==3 && e.exchanges==1);
    h.offset++; n.word=owner; e=(struct execution){0}; run(&h,&e);
    assert(n.word==owner && !e.exchanges && !e.wakes);
    e=(struct execution){.fault=(uintptr_t)&h}; run(&h,&e);
    assert(e.reads==1 && !e.exchanges && !e.wakes);
}
extern char md_guest_compare_exchange[],md_guest_exchanged[],md_raw_syscall[];
extern char md_test_task_call[],md_test_task_return[],md_test_task_completed[];
static struct md_interception_abi retained_abi(void) {
    return (struct md_interception_abi){.compare_exchange=(uintptr_t)md_guest_compare_exchange,
        .exchanged=(uintptr_t)md_guest_exchanged,.task_call=(uintptr_t)md_test_task_call,
        .task_return=(uintptr_t)md_test_task_return,.task_completed=(uintptr_t)md_test_task_completed};
}
static long cursor_trace(int request,pid_t pid,void *address,void *data) {
    assert(request==PTRACE_GETREGSET && pid==getpid() && (uintptr_t)address==NT_PRSTATUS);
    struct iovec *io=data; assert(io->iov_len==sizeof(struct user_pt_regs));
    memset(io->iov_base,0,io->iov_len); return 0;
}
static void scheduling(void) {
    struct node n={.word=owner+1}; struct md_robust_head h=head_for(&n);
    n.next=(uintptr_t)&n.next;
    struct md_interception_abi abi=retained_abi();
    struct md_task_robust task={0};
    assert(md_task_robust_begin(&task,cursor_trace,getpid(),&abi,(uintptr_t)&h,0)==-EINVAL);
    int state=md_task_robust_begin(&task,cursor_trace,getpid(),&abi,(uintptr_t)&h,owner);
    assert(state==2 && !task.running);
    assert(md_task_robust_begin(&task,cursor_trace,getpid(),&abi,0,owner)==-EBUSY);
    unsigned turns=1;
    while (state==2) { assert(++turns<512); state=md_task_robust_progress(&task); }
    assert(state==1 && turns>1 && n.word==owner+1);
    assert(md_task_robust_begin(&task,cursor_trace,getpid(),&abi,0,owner)==1);
}
static void retained_termination(void) {
    int statuses[]={SIGKILL,0,(PTRACE_EVENT_EXIT<<16)|(SIGTRAP<<8)|0x7f,
        (PTRACE_EVENT_EXEC<<16)|(SIGTRAP<<8)|0x7f};
    for (unsigned i=0;i<sizeof(statuses)/sizeof(*statuses);++i) {
        struct md_task_retained r={.parent={.actor=100},.actor=200,.stage=1,.parent_ready=1};
        assert(md_task_retained_event(&r,100,statuses[i])==-EAGAIN);
        assert(r.parent_terminal && r.actor==200);
    }
}
struct native_shared {
    uintptr_t head; uint32_t word; pid_t waiter;
    struct { pid_t tid; int descriptor; } readers[2];
};
static struct md_task_child_reap_scratch signal_exchange;
static void registers(pid_t pid,struct user_pt_regs *r,int write) {
    struct iovec io={r,sizeof(*r)};
    assert(!ptrace(write ? PTRACE_SETREGSET : PTRACE_GETREGSET,pid,(void *)NT_PRSTATUS,&io));
}
static void trace_marker(void) {
#define CHECKPOINT(at) \
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4), \
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(at)>>32,0,3), \
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)), \
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)(at),0,1), \
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_CHECKPOINT)
    struct sock_filter code[]={
        CHECKPOINT((uintptr_t)md_guest_exchanged+4),
        CHECKPOINT((uintptr_t)md_test_task_completed+4),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
#undef CHECKPOINT
    struct sock_fprog filter={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&filter));
}
static pid_t waiter_status(pid_t pid) {
    int status; assert(waitpid(pid,&status,__WALL)==pid); return status;
}
static long native_trace(int request,pid_t pid,void *address,void *data) {
    return ptrace(request,pid,address,data);
}
static int restricted(pid_t pid) { (void)pid; return 1; }
static int marker(uintptr_t pc) {
    return pc==(uintptr_t)md_guest_exchanged+4 || pc==(uintptr_t)md_test_task_completed+4;
}
static struct md_task_checkpoints checkpoints={.trace=native_trace,
    .restricted=restricted,.marker=marker,.begin=1,.end=UINTPTR_MAX};
static long checkpoint_trace(int request,pid_t pid,void *address,void *data) {
    return md_task_checkpoint_trace(&checkpoints,request,pid,address,data);
}
static pid_t borrowed_actor;
static long retained_trace(int request,pid_t pid,void *address,void *data) {
    return pid==borrowed_actor ? native_trace(request,pid,address,data)
        : checkpoint_trace(request,pid,address,data);
}
static pid_t retain_mm(pid_t pid) {
    struct user_pt_regs saved,restored; registers(pid,&saved,0);
    struct md_task_retained retained={0};
    struct md_interception_abi abi=retained_abi();
    borrowed_actor=pid;
    assert(!md_task_retained_begin(&retained,retained_trace,pid,MD_TASK_CALL_USER,&saved,&abi));
    int ready=0,early_status=0; pid_t early=0;
    while (!ready) {
        int status; pid_t actor;
        if (early && early==retained.actor) { actor=early; status=early_status; early=0; }
        else { actor=waitpid(-1,&status,__WALL); assert(actor>0); }
        if (actor!=pid && actor!=retained.actor) {
            assert(!early && !retained.actor && WIFSTOPPED(status));
            early=actor; early_status=status; continue;
        }
        if (actor!=pid) {
            int handled=md_task_checkpoint_event(&checkpoints,actor,&status); assert(handled>=0);
            if (handled) continue;
        }
        ready=md_task_retained_event(&retained,actor,status); assert(ready>=0);
    }
    registers(pid,&restored,0);
    assert(!memcmp(&saved,&restored,sizeof(saved)));
    assert(!early && retained.actor>0);
    borrowed_actor=0;
    return retained.actor;
}
static void seize(pid_t child) {
    unsigned long options=PTRACE_O_TRACESYSGOOD|PTRACE_O_TRACEFORK|PTRACE_O_TRACECLONE
        |PTRACE_O_TRACEEXEC|PTRACE_O_TRACEEXIT|PTRACE_O_TRACESECCOMP|PTRACE_O_EXITKILL;
    assert(!ptrace(PTRACE_SEIZE,child,NULL,(void *)options));
}
static void reap_private(pid_t pid,pid_t group,pid_t agent,pid_t real,int code,int value,int executing) {
    /* The new application has not run. Reap only the owned private child;
     * restoring the exec syscall exit must preserve every entry register. */
    if (executing) {
        assert(!ptrace(PTRACE_SYSCALL,pid,NULL,NULL));
        int status=waiter_status(pid);
        assert(WIFSTOPPED(status) && WSTOPSIG(status)==(SIGTRAP|0x80));
    }
    enum md_task_call_boundary boundary=executing ? MD_TASK_CALL_EXIT : MD_TASK_CALL_USER;
    struct user_pt_regs saved,restored;
    registers(pid,&saved,0);
    siginfo_t displaced={0};
    if (real) {
        displaced=(siginfo_t){.si_signo=SIGCHLD,.si_code=code};
        displaced.si_pid=real; displaced.si_uid=getuid(); displaced.si_status=value;
    }
    struct md_task_child_reap reap={0};
    assert(!md_task_child_reap_begin(&reap,native_trace,pid,group,agent,boundary,&saved,
        (uintptr_t)md_raw_syscall,(uintptr_t)md_raw_syscall+4,
        &signal_exchange,(uintptr_t)&signal_exchange,&displaced));
    int ready=0;
    while (!ready) {
        int status=waiter_status(pid);
        ready=md_task_child_reap_event(&reap,status);
        if (ready<0) fprintf(stderr,"reap error=%d actor=%d group=%d stage=%d call-stage=%u boundary=%u result=%ld status=%#x\n",
            ready,pid,group,reap.stage,reap.call.stage,reap.call.boundary,reap.call.result,status);
        assert(ready>=0);
    }
    registers(pid,&restored,0);
    assert(!memcmp(&saved,&restored,sizeof(saved)));
}
static void *native_waiter(void *arg) {
    struct native_shared *s=arg;
    __atomic_store_n(&s->waiter,(pid_t)syscall(SYS_gettid),__ATOMIC_RELEASE);
    struct timespec deadline={.tv_sec=10};
    assert(!syscall(SYS_futex,&s->word,FUTEX_WAIT,owner|FUTEX_WAITERS,&deadline,NULL,0));
    assert(__atomic_load_n(&s->word,__ATOMIC_ACQUIRE)==(FUTEX_OWNER_DIED|FUTEX_WAITERS));
    return NULL;
}
static void await_syscall(pid_t group,pid_t tid,long number,unsigned long argument) {
    /* Prove the waiter entered the kernel; a ready flag before futex() would
     * let cleanup win without actually exercising the wake. Test-only probe. */
    struct timespec begin,now; assert(!clock_gettime(CLOCK_MONOTONIC,&begin));
    for (;;) {
        if (tid) {
            char path[96]; snprintf(path,sizeof(path),"/proc/%d/task/%d/syscall",group,tid);
            FILE *f=fopen(path,"r"); assert(f);
            long nr=-1; unsigned long at=0;
            int fields=fscanf(f,"%ld %lx",&nr,&at); fclose(f);
            if (fields==2 && nr==number && at==argument) return;
        }
        assert(!clock_gettime(CLOCK_MONOTONIC,&now));
        assert(now.tv_sec-begin.tv_sec<5);
        sched_yield();
    }
}
static void await_waiter(struct native_shared *s) {
    struct timespec begin,now; assert(!clock_gettime(CLOCK_MONOTONIC,&begin));
    pid_t tid;
    while (!(tid=__atomic_load_n(&s->waiter,__ATOMIC_ACQUIRE))) {
        assert(!clock_gettime(CLOCK_MONOTONIC,&now) && now.tv_sec-begin.tv_sec<5);
        sched_yield();
    }
    await_syscall(getpid(),tid,SYS_futex,(uintptr_t)&s->word);
}
static void native_cleanup(pid_t agent,uintptr_t head) {
    struct md_interception_abi abi=retained_abi();
    struct md_task_robust task={0};
    int state=md_task_robust_begin(&task,checkpoint_trace,agent,&abi,head,owner);
    while (state!=1) {
        assert(state>=0);
        if (state==2) { state=md_task_robust_progress(&task); continue; }
        int status=waiter_status(agent);
        int handled=md_task_checkpoint_event(&checkpoints,agent,&status); assert(handled>=0);
        if (!handled) state=md_task_robust_event(&task,status);
    }
    md_task_checkpoints_clear(&checkpoints);
}
static void native_list(struct native_shared *shared,const char *mode,const char *executable,pid_t real) {
    struct node n={0}; struct md_robust_head h=head_for(&n);
    h.offset=(uintptr_t)&shared->word-(uintptr_t)&n.next;
    n.next=(uintptr_t)&h;
    shared->head=(uintptr_t)&h; shared->word=owner|FUTEX_WAITERS;
    if (!strcmp(mode,"exec-fault")) {
        struct sigaction action={.sa_handler=SIG_IGN}; assert(!sigaction(SIGSEGV,&action,NULL));
        assert(!mprotect(shared,(size_t)getpagesize(),PROT_READ));
    }
    assert(!raise(SIGSTOP));
    if (!strncmp(mode,"exec",4)) {
        char id[32]; snprintf(id,sizeof(id),"%d",real);
        execl(executable,executable,"--continued",id,mode,NULL);
        _exit(121);
    }
    _exit(0);
}
static void check_notifications(pid_t real,int code,int value,const char *mode) {
    sigset_t mask; sigemptyset(&mask); sigaddset(&mask,SIGCHLD);
    int fd=signalfd(-1,&mask,SFD_NONBLOCK|SFD_CLOEXEC); assert(fd>=0);
    struct signalfd_siginfo notice;
    int ignored=!strcmp(mode,"subreaper-ignore"),no_wait=ignored || !strcmp(mode,"subreaper-nochildwait");
    if (!strcmp(mode,"exec-directed")) {
        assert(read(fd,&notice,sizeof(notice))==sizeof(notice));
        assert(notice.ssi_signo==SIGCHLD && notice.ssi_code==SI_QUEUE
            && notice.ssi_pid==(unsigned)getpid() && notice.ssi_int==123);
    }
    if (real && !ignored) {
        assert(read(fd,&notice,sizeof(notice))==sizeof(notice));
        assert(notice.ssi_signo==SIGCHLD && notice.ssi_code==code
            && notice.ssi_pid==(unsigned)real && notice.ssi_status==value);
        int status; pid_t result=waitpid(real,&status,0);
        if (no_wait) assert(result==-1 && errno==ECHILD);
        else {
            assert(result==real);
            assert(code==CLD_EXITED ? WIFEXITED(status) && WEXITSTATUS(status)==value
                : WIFSIGNALED(status) && WTERMSIG(status)==value);
        }
    }
    assert(read(fd,&notice,sizeof(notice))==-1 && errno==EAGAIN); close(fd);
    int status;
    assert(waitpid(-1,&status,__WALL|WNOHANG)==-1 && errno==ECHILD);
}
struct native_reader { struct native_shared *shared; pid_t child; unsigned index; int killed; };
static void *native_reader(void *context) {
    struct native_reader *r=context;
    int fd=-1;
    if (!r->index) {
        sigset_t set; sigemptyset(&set); sigaddset(&set,SIGCHLD);
        fd=signalfd(-1,&set,SFD_CLOEXEC); assert(fd>=0);
    }
    r->shared->readers[r->index].descriptor=fd;
    __atomic_store_n(&r->shared->readers[r->index].tid,(pid_t)syscall(SYS_gettid),__ATOMIC_RELEASE);
    if (!r->index) {
        struct signalfd_siginfo info;
        assert(read(fd,&info,sizeof(info))==sizeof(info));
        assert(info.ssi_pid==(unsigned)r->child && info.ssi_code==(r->killed ? CLD_KILLED : CLD_EXITED)
            && info.ssi_status==(r->killed ? SIGKILL : 0));
        assert(!fcntl(fd,F_SETFL,O_NONBLOCK));
        assert(read(fd,&info,sizeof(info))==-1 && errno==EAGAIN); close(fd);
    } else {
        int status; assert(waitpid(r->child,&status,0)==r->child);
        assert(r->killed ? WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL
            : WIFEXITED(status) && !WEXITSTATUS(status));
    }
    return NULL;
}
static void native_owner(struct native_shared *shared,const char *mode,const char *executable) {
    assert(!raise(SIGSTOP));
    trace_marker();
    sigset_t mask; sigemptyset(&mask); sigaddset(&mask,SIGCHLD);
    assert(!sigprocmask(SIG_BLOCK,&mask,NULL));
    pid_t real=0;
    if (!strcmp(mode,"exec-directed")) {
        siginfo_t info={.si_signo=SIGCHLD,.si_code=SI_QUEUE};
        info.si_pid=getpid(); info.si_uid=getuid(); info.si_value.sival_int=123;
        assert(!syscall(SYS_rt_tgsigqueueinfo,getpid(),syscall(SYS_gettid),SIGCHLD,&info));
    }
    if (!strcmp(mode,"exec-real-first") || !strcmp(mode,"exec-private-first") || !strcmp(mode,"exec-directed")) {
        real=fork(); assert(real>=0);
        if (!real) _exit(47);
    }
    if (!strncmp(mode,"subreaper",9)) {
        assert(!prctl(PR_SET_CHILD_SUBREAPER,1,0,0,0));
        if (!strcmp(mode,"subreaper-ignore") || !strcmp(mode,"subreaper-nochildwait")) {
            struct sigaction action={.sa_handler=!strcmp(mode,"subreaper-ignore") ? SIG_IGN : SIG_DFL,
                .sa_flags=SA_NOCLDWAIT};
            assert(!sigaction(SIGCHLD,&action,NULL));
        }
        real=fork(); assert(real>=0);
        if (!real) native_list(shared,mode,executable,0);
        int readers=!strncmp(mode,"subreaper-reader",16);
        struct native_reader arguments[2]; pthread_t threads[2];
        if (readers) for (unsigned i=0;i<2;++i) {
            arguments[i]=(struct native_reader){shared,real,i,strstr(mode,"kill")!=NULL};
            assert(!pthread_create(threads+i,NULL,native_reader,arguments+i));
        }
        assert(!raise(SIGSTOP));
        if (readers) {
            for (unsigned i=0;i<2;++i) assert(!pthread_join(threads[i],NULL));
            check_notifications(0,0,0,mode);
        } else check_notifications(real,!strcmp(mode,"subreaper-kill") ? CLD_KILLED : CLD_EXITED,
                !strcmp(mode,"subreaper-kill") ? SIGKILL : 0,mode);
        _exit(0);
    }
    native_list(shared,mode,executable,real);
}
static void native(const char *mode,const char *executable) {
    int executing=!strncmp(mode,"exec",4);
    int racing=!strcmp(mode,"exec-real-first") || !strcmp(mode,"exec-private-first") || !strcmp(mode,"exec-directed");
    int readers=!strcmp(mode,"subreaper-readers") || !strcmp(mode,"subreaper-readers-kill")
        || !strcmp(mode,"subreaper-reader-reap");
    int fault=!strcmp(mode,"exec-fault");
    int subreaper=!strcmp(mode,"subreaper-exit") || !strcmp(mode,"subreaper-kill")
        || !strcmp(mode,"subreaper-ignore") || !strcmp(mode,"subreaper-nochildwait") || readers;
    assert(!strcmp(mode,"exit") || !strcmp(mode,"kill") || !strcmp(mode,"exec") || racing || subreaper || fault);
    assert(!prctl(PR_SET_CHILD_SUBREAPER,1,0,0,0));
    struct native_shared *s=mmap(NULL,sizeof(*s),PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(s!=MAP_FAILED);
    int channel[2]; assert(!pipe2(channel,O_CLOEXEC));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(channel[1]); char ready; assert(read(channel[0],&ready,1)==1); close(channel[0]);
        native_owner(s,mode,executable);
    }
    close(channel[0]); seize(child);
    assert(write(channel[1],"r",1)==1); close(channel[1]);
    int status=waiter_status(child);
    assert(WIFSTOPPED(status) && WSTOPSIG(status)==SIGSTOP);
    assert(!ptrace(PTRACE_CONT,child,NULL,NULL));
    status=waiter_status(child);
    pid_t real=0,parent=0;
    pid_t reader_ids[2]={0}; unsigned reader_count=0;
    if (racing || subreaper) {
        assert(WIFSTOPPED(status) && status>>16==PTRACE_EVENT_FORK);
        unsigned long born; assert(!ptrace(PTRACE_GETEVENTMSG,child,NULL,&born)); real=(pid_t)born;
        status=waiter_status(real); assert(WIFSTOPPED(status));
        assert(!ptrace(PTRACE_CONT,child,NULL,NULL));
        status=waiter_status(child);
        if (subreaper) {
            while (readers && WIFSTOPPED(status) && status>>16==PTRACE_EVENT_CLONE) {
                assert(reader_count<2);
                unsigned long tid; assert(!ptrace(PTRACE_GETEVENTMSG,child,NULL,&tid));
                reader_ids[reader_count++]=(pid_t)tid;
                status=waiter_status((pid_t)tid); assert(WIFSTOPPED(status));
                assert(!ptrace(PTRACE_CONT,(pid_t)tid,NULL,NULL));
                assert(!ptrace(PTRACE_CONT,child,NULL,NULL));
                status=waiter_status(child);
            }
            assert(WIFSTOPPED(status) && WSTOPSIG(status)==SIGSTOP && !(status>>16));
            if (readers) {
                assert(reader_count==2);
                for (unsigned i=0;i<2;++i) {
                    struct timespec begin,now; assert(!clock_gettime(CLOCK_MONOTONIC,&begin));
                    pid_t tid;
                    while (!(tid=__atomic_load_n(&s->readers[i].tid,__ATOMIC_ACQUIRE))) {
                        assert(!clock_gettime(CLOCK_MONOTONIC,&now) && now.tv_sec-begin.tv_sec<5);
                        sched_yield();
                    }
                    await_syscall(child,tid,i ? SYS_wait4 : SYS_read,i ? (unsigned long)real : (unsigned long)s->readers[i].descriptor);
                }
            }
            parent=child; child=real; real=0;
            assert(!ptrace(PTRACE_CONT,child,NULL,NULL));
            status=waiter_status(child);
        }
    }
    assert(WIFSTOPPED(status) && WSTOPSIG(status)==SIGSTOP && !(status>>16));
    pid_t agent=retain_mm(child);
    pthread_t waiter;
    if (!fault) { assert(!pthread_create(&waiter,NULL,native_waiter,s)); await_waiter(s); }
    int killed=strstr(mode,"kill")!=NULL;
    if (killed) assert(!kill(child,SIGKILL));
    else assert(!ptrace(PTRACE_CONT,child,NULL,NULL));
    status=waiter_status(child);
    assert(WIFSTOPPED(status) && status>>16==(executing ? PTRACE_EVENT_EXEC : PTRACE_EVENT_EXIT));
    native_cleanup(agent,s->head);
    if (fault) assert(s->word==(owner|FUTEX_WAITERS));
    else assert(!pthread_join(waiter,NULL));
    struct md_task_quiesce *barrier=NULL;
    if (readers) {
        barrier=md_task_quiesce_new(native_trace); assert(barrier);
        assert(!md_task_quiesce_add(barrier,parent,1,PTRACE_CONT));
        for (unsigned i=0;i<2;++i) assert(!md_task_quiesce_add(barrier,reader_ids[i],0,PTRACE_CONT));
        assert(!md_task_quiesce_stop(barrier));
        for (unsigned i=0;i<2;++i) assert(!md_task_quiesce_event(barrier,reader_ids[i],waiter_status(reader_ids[i]),NULL));
        assert(md_task_quiesce_ready(barrier));
    }
    if (racing && !strcmp(mode,"exec-real-first")) {
        assert(!ptrace(PTRACE_CONT,real,NULL,NULL));
        status=waiter_status(real); assert(WIFSTOPPED(status) && status>>16==PTRACE_EVENT_EXIT);
        assert(!ptrace(PTRACE_CONT,real,NULL,NULL));
        status=waiter_status(real); assert(WIFEXITED(status) && WEXITSTATUS(status)==47);
    }
    assert(!kill(agent,SIGKILL));
    status=waiter_status(agent); assert(WIFSTOPPED(status) && status>>16==PTRACE_EVENT_EXIT);
    assert(!ptrace(PTRACE_CONT,agent,NULL,NULL));
    status=waiter_status(agent); assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
    if (racing && strcmp(mode,"exec-real-first")) {
        assert(!ptrace(PTRACE_CONT,real,NULL,NULL));
        status=waiter_status(real); assert(WIFSTOPPED(status) && status>>16==PTRACE_EVENT_EXIT);
        assert(!ptrace(PTRACE_CONT,real,NULL,NULL));
        status=waiter_status(real); assert(WIFEXITED(status) && WEXITSTATUS(status)==47);
    }
    if (executing) reap_private(child,child,agent,real,CLD_EXITED,47,1);
    assert(!ptrace(PTRACE_CONT,child,NULL,NULL));
    if (executing) {
        status=waiter_status(child);
        assert(WIFSTOPPED(status) && status>>16==PTRACE_EVENT_EXIT);
        assert(!ptrace(PTRACE_CONT,child,NULL,NULL));
    }
    status=waiter_status(child);
    assert(killed ? WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL
        : WIFEXITED(status) && !WEXITSTATUS(status));
    if (subreaper) {
        struct md_task_quiesce_stop stopped;
        pid_t actor=!strcmp(mode,"subreaper-reader-reap") ? reader_ids[1] : parent;
        if (barrier) assert(!md_task_quiesce_borrow(barrier,actor,&stopped)
            && stopped.kind==(actor==parent ? MD_TASK_QUIESCE_HELD : MD_TASK_QUIESCE_SYNTHETIC));
        reap_private(actor,parent,agent,child,killed ? CLD_KILLED : CLD_EXITED,killed ? SIGKILL : 0,0);
        if (barrier) {
            assert(!md_task_quiesce_return(barrier,actor));
            assert(!md_task_quiesce_release(barrier));
            assert(!md_task_quiesce_take(barrier,&status,NULL));
            md_task_quiesce_destroy(barrier);
        }
        assert(!ptrace(PTRACE_CONT,parent,NULL,NULL));
        for (unsigned i=0;i<reader_count;++i) {
            status=waiter_status(reader_ids[i]); assert(WIFSTOPPED(status) && status>>16==PTRACE_EVENT_EXIT);
            assert(!ptrace(PTRACE_CONT,reader_ids[i],NULL,NULL));
            status=waiter_status(reader_ids[i]); assert(WIFEXITED(status) && !WEXITSTATUS(status));
        }
        status=waiter_status(parent); assert(WIFSTOPPED(status) && status>>16==PTRACE_EVENT_EXIT);
        assert(!ptrace(PTRACE_CONT,parent,NULL,NULL));
        status=waiter_status(parent); assert(WIFEXITED(status) && !WEXITSTATUS(status));
    } else if (!executing) {
        /* After real-parent exit the subreaper owns the already traced zombie. */
        status=waiter_status(agent); assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
    }
    assert(waitpid(-1,&status,__WALL|WNOHANG)==-1 && errno==ECHILD);
    assert(!munmap(s,sizeof(*s)));
    printf("PASS robust executor: %s, retained mm, atomic ownership, sleeping waiter, complete reap\n",mode);
}
int main(int argc,char **argv) {
    alarm(30);
    if (argc==4 && !strcmp(argv[1],"--continued")) {
        check_notifications(atoi(argv[2]),CLD_EXITED,47,argv[3]);
        if (!strcmp(argv[3],"exec-fault")) {
            struct sigaction action; assert(!sigaction(SIGSEGV,NULL,&action));
            assert(action.sa_handler==SIG_IGN);
        }
        return 0;
    }
    if (argc==3 && !strcmp(argv[1],"--native")) { native(argv[2],argv[0]); return 0; }
    assert(argc==1);
    ordinary(); races(); incomplete(); malformed(); scheduling(); retained_termination();
    puts("PASS robust futex cursor: owner races, waiters, pending operation, malformed list");
    return 0;
}
