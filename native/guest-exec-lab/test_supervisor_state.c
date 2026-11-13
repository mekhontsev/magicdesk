/* Compile the actual coordinator; only external admission/accounting boundaries
 * are fault-injected. No duplicate model of its state machine or product hooks. */
#define main supervisor_program_main
#include "../shroot/src/supervisor.c"
#undef main
#include "../shroot/src/supervisor_identity.c"
#include "../shroot/src/supervisor_signals.c"
#include "../shroot/src/supervisor_seccomp.c"
#include "../shroot/src/supervisor_readiness.c"
#include "../shroot/src/supervisor_task.c"
#include "../shroot/src/supervisor_execution.c"
#include "../shroot/src/supervisor_transfer.c"
#include "../shroot/src/supervisor_domain.c"
#include "../shroot/src/supervisor_descriptors.c"
#include "../shroot/src/supervisor_actors.c"
#include "../shroot/src/supervisor_memory.c"
#include "../shroot/src/supervisor_fs.c"
#include "../shroot/src/supervisor_proc.c"
#include "../shroot/src/supervisor_notifications.c"
#include "../shroot/src/supervisor_io.c"
#include "../shroot/src/supervisor_debug.c"
#include "../shroot/src/supervisor_robust.c"
#include <assert.h>
#include "fs_mount_internal.h"
#include "seccomp_notify.h"
#include "task_pending.h"
#include "full_device.h"

static void filesystem_control_metadata(void) {
    struct stat st={.st_mode=S_IFCHR|0666};
    struct md_control_file control=md_full_device_new(&st,NULL); assert(control.ops);
    struct md_identity identity={0};
    struct md_control_opener opener={.identity=&identity};
    for (unsigned path=0;path<2;++path) {
        struct md_proc_file *file=md_proc_file_open_control(control.ops,control.context,&opener,
            path ? O_PATH : O_RDWR); assert(file);
        struct md_control_file selected=control;
        assert(!control_file_query(md_proc_file_object(file),MD_FS_FSTAT,&selected));
        assert(!selected.ops && !selected.context);
        assert(control_file_query(md_proc_file_object(file),MD_FS_FDINFO,&selected)==1);
        assert(selected.ops==control.ops && selected.context==control.context);
        md_proc_file_release(file);
    }
    md_control_file_release(control);
    puts("PASS filesystem device metadata: inode-owned stat, retained IO and descriptor information");
}

static unsigned pending_test_count,pending_test_calls,pending_test_seen;
static unsigned borrowed_resumes;
static void scalar_stopped_transport(void) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!ptrace(PTRACE_TRACEME,0,NULL,NULL));
        raise(SIGSTOP);
        _exit(0);
    }
    int status;
    assert(waitpid(child,&status,0)==child && WIFSTOPPED(status));
    struct thread t={.pid=child,.kernel_stopped=1};
    struct user_pt_regs expected,actual;
    struct iovec io={&expected,sizeof(expected)};
    assert(!ptrace(PTRACE_GETREGSET,child,(void *)NT_PRSTATUS,&io));
    const long values[]={0,7,-EINTR,-EACCES,RESTART_SIGNAL,LONG_MIN,LONG_MAX};
    for (unsigned i=0;i<sizeof(values)/sizeof(*values);++i) {
        assert(set_stopped_result(&t,values[i]));
        struct { uint64_t before; long result; uint64_t after; } out={0x12345678,0,0x87654321};
        assert(stopped_result(&t,&out.result) && out.result==values[i]);
        assert(out.before==0x12345678 && out.after==0x87654321);
        io=(struct iovec){&actual,sizeof(actual)};
        assert(!ptrace(PTRACE_GETREGSET,child,(void *)NT_PRSTATUS,&io));
        expected.regs[0]=(uint64_t)values[i];
        assert(!memcmp(&actual,&expected,sizeof(actual)));
    }
    assert(!kill(child,SIGKILL));
    assert(waitpid(child,&status,0)==child && WIFSIGNALED(status));
    puts("PASS scalar stopped transport: signed results, bounded reads and unchanged arguments, PC, SP and PSTATE");
}
static void terminal_cleanup_custody(void) {
    assert(!task_wait.cursor);
    for (unsigned terminal=0;terminal<2;++terminal) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            assert(!ptrace(PTRACE_TRACEME,0,NULL,NULL));
            raise(SIGSTOP);
            for (;;) pause();
        }
        int status;
        assert(waitpid(child,&status,0)==child && WIFSTOPPED(status));
        assert(!ptrace(PTRACE_SETOPTIONS,child,NULL,(void *)(uintptr_t)PTRACE_O_TRACEEXIT));
        if (terminal) {
            assert(!ptrace(PTRACE_CONT,child,NULL,NULL));
            assert(!kill(child,SIGKILL));
            assert(waitpid(child,&status,__WALL)==child && (unsigned)status>>16==PTRACE_EVENT_EXIT);
        }
        /* Already consumed by a barrier; no guest thread entry is required. */
        struct md_task_wait_member member={0};
        md_task_wait_track(&task_wait,&member,child);
        cleanup_resume_tracees();
        do {
            assert(waitpid(child,&status,__WALL)==child);
            if (WIFSTOPPED(status)) assert(!ptrace(PTRACE_CONT,child,NULL,(void *)(uintptr_t)SIGKILL));
        } while (WIFSTOPPED(status));
        assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
        md_task_wait_untrack(&task_wait,&member);
    }
    assert(!task_wait.cursor);
    puts("PASS fatal cleanup custody: held child and consumed TRACEEXIT retire without another kernel stop");
}
static void native_child_membership(void) {
    int channel[2]; assert(!pipe(channel));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(channel[0]);
        assert(!prctl(PR_SET_NAME,"name ) with (",0,0,0));
        assert(write(channel[1],"x",1)==1);
        for (;;) pause();
    }
    close(channel[1]); char byte;
    assert(read(channel[0],&byte,1)==1); close(channel[0]);
    assert(md_task_parent(child)==getpid());
    pid_t group=0; int signal=-1;
    assert(!md_task_child_class(child,&group,&signal));
    assert(group==getpgrp() && signal==SIGCHLD);
    struct thread *saved=threads;
    struct thread descendant={.pid=child,.tgid=child,.born=1};
    struct thread parent={.pid=getpid(),.tgid=getpid(),.born=1,.next=&descendant};
    threads=&parent;
    assert(retained_retirement_group_ready(parent.pid));
    for (struct thread *t=threads;t;t=t->next) {
        t->terminal_stop=1;
        assert(!retained_retirement_group_ready(parent.pid));
        assert(retained_retirement_group_ready(-1));
        t->terminal_stop=0; t->robust.terminal=1;
        assert(!retained_retirement_group_ready(parent.pid));
        t->robust.terminal=0;
        assert(retained_retirement_group_ready(parent.pid));
    }
    threads=saved;
    assert(!kill(child,SIGKILL));
    int status; assert(waitpid(child,&status,0)==child && WIFSIGNALED(status));
    assert(md_task_parent(child)==-ENOENT);
    puts("PASS native child classification and retirement: parent/child exit ownership, wait class and parenthesized comm");
}
static int wait_test_hidden,wait_test_done,wait_test_admitted=1;
static pid_t wait_test_last;
static long wait_test_result;
static enum md_debugger_wait_action wait_test_action;
static struct seccomp_data wait_test_probe;
static int wait_test_children(pid_t pid,const struct seccomp_data *call,pid_t **candidates,size_t *count) {
    (void)pid; (void)call;
    *candidates=NULL; *count=0;
    if (wait_test_hidden && wait_test_last) {
        *candidates=calloc(wait_test_last,sizeof(**candidates)); assert(*candidates);
        *count=wait_test_last;
        for (pid_t i=0;i<wait_test_last;++i) (*candidates)[i]=i+1;
    }
    return wait_test_hidden;
}
static void wait_test_complete(pid_t pid,long result) {
    (void)pid;
    ++wait_test_done; wait_test_result=result;
}
static int wait_test_admit(pid_t pid) { (void)pid; return wait_test_admitted; }
static void wait_test_run(pid_t pid,const struct seccomp_data *call,enum md_debugger_wait_action action) {
    (void)pid;
    wait_test_probe=*call; wait_test_action=action;
}
int __real_md_debugger_active(struct md_debugger *);
static void private_wait_selection(void) {
    struct md_debugger_host host={.wait_children=wait_test_children,.complete=wait_test_complete,
        .wait=wait_test_run,.wait_admit=wait_test_admit};
    struct md_debugger *d=md_debugger_create(&host); assert(d);
    struct user_pt_regs regs={.regs={[8]=SYS_wait4,[0]=(unsigned long)-1,[2]=WNOHANG|__WALL}};
    struct seccomp_data call=syscall_description(&regs);
    wait_test_hidden=0; wait_test_last=2;
    assert(md_debugger_call(d,123,call)==MD_DEBUG_CALL_NATIVE);
    wait_test_hidden=1; wait_test_last=0;
    assert(md_debugger_call(d,123,call)==MD_DEBUG_CALL_HANDLED && wait_test_result==-ECHILD);
    for (unsigned eligible=0;eligible<2;++eligible) {
        wait_test_last=2; wait_test_done=0;
        wait_test_admitted=0;
        assert(md_debugger_call(d,123,call)==MD_DEBUG_CALL_CONTEXT);
        assert(!wait_test_done && !__real_md_debugger_active(d));
        wait_test_admitted=1;
        assert(md_debugger_call(d,123,call));
        assert(wait_test_action==MD_DEBUG_WAIT_PROBE && wait_test_probe.args[0]==1);
        md_debugger_wait_complete(d,123,-ECHILD,NULL);
        assert(!wait_test_done && wait_test_action==MD_DEBUG_WAIT_REPROBE && wait_test_probe.args[0]==2);
        md_debugger_wait_complete(d,123,eligible ? 0 : -ECHILD,NULL);
        assert(wait_test_done==1 && wait_test_result==(eligible ? 0 : -ECHILD));
    }
    call.args[2]=__WALL; wait_test_last=1; wait_test_done=0;
    assert(md_debugger_call(d,123,call));
    md_debugger_wait_complete(d,123,0,NULL);
    assert(!wait_test_done && wait_test_action==MD_DEBUG_WAIT_PARK);
    md_debugger_wait_resumed(d,123,0);
    assert(wait_test_action==MD_DEBUG_WAIT_REPROBE && wait_test_probe.args[0]==1);
    md_debugger_wait_complete(d,123,1,NULL);
    assert(wait_test_done==1 && wait_test_result==1);
    call=(struct seccomp_data){.nr=SYS_waitid,.args={P_ALL,0,0,WEXITED|WNOWAIT|__WALL}};
    wait_test_done=0;
    assert(md_debugger_call(d,123,call));
    union md_process_wait info={.info={.si_signo=SIGCHLD,.si_pid=1,.si_code=CLD_EXITED}};
    md_debugger_wait_complete(d,123,0,&info);
    assert(wait_test_done==1 && !wait_test_result);
    wait_test_done=0;
    assert(md_debugger_call(d,123,call) && __real_md_debugger_active(d));
    md_debugger_rekey(d,123,456);
    assert(!__real_md_debugger_active(d) && !wait_test_done);
    md_debugger_destroy(d);
    puts("PASS private-child wait selection: native admission, exact candidates, ECHILD and event-driven retry");
}
static void private_wait_buffer_cancel(void) {
    struct thread t={.pid=123,.memory=md_memory_new()};
    assert(t.memory && !md_memory_buffers_prepare(t.memory,1,sizeof(siginfo_t),0,0));
    struct md_interception_buffer held={0};
    assert(!md_buffers_take(md_memory_buffers(t.memory),&held));
    unsigned bucket=(unsigned)t.pid%THREAD_BUCKETS;
    assert(!thread_index[bucket]); thread_index[bucket]=&t;
    struct user_pt_regs regs={.regs={[8]=SYS_waitid,[0]=P_ALL,[3]=WEXITED|__WALL}};
    const struct seccomp_data call=syscall_description(&regs);
    execution_retain(&t,&call,&regs);
    for (unsigned retry=0;retry<2;++retry) {
        debugger_wait(t.pid,&call,retry ? MD_DEBUG_WAIT_REPROBE : MD_DEBUG_WAIT_PROBE);
        assert(t.phase==DEBUG_WAIT_BUFFER && t.debug.wait_output);
        assert(!t.debug.wait_output->buffer.pool);
        assert(!memcmp(&t.execution.registers,&regs,sizeof(regs)));
        assert(!memcmp(&t.debug.wait_output->call,&call,sizeof(call)));
        assert(t.debug.wait_output->action==(retry ? MD_DEBUG_WAIT_REPROBE : MD_DEBUG_WAIT_PROBE));
        debugger_wait_release(&t);
        assert(!t.debug.wait_output && !t.retries[0].node.list && !t.retries[1].node.list);
    }
    assert(!md_buffer_release(&held));
    assert(!md_buffers_take(md_memory_buffers(t.memory),&held));
    assert(!md_buffer_release(&held));
    md_memory_release(t.memory); thread_index[bucket]=NULL;
    puts("PASS private-child wait output: exhausted shared pool, entry/reprobe cancellation and no leaked lease");
}
static long parked_checkpoint_trace(int request,pid_t pid,void *address,void *data) {
    assert(pid==getpid());
    if (request==PTRACE_GET_SYSCALL_INFO)
        *(struct ptrace_syscall_info *)data=(struct ptrace_syscall_info){.op=PTRACE_SYSCALL_INFO_EXIT};
    else if (request==PTRACE_GETSIGMASK) *(uint64_t *)data=0;
    else assert(request==PTRACE_SETREGSET || request==PTRACE_SETSIGMASK || request==PTRACE_SYSCALL);
    (void)address;
    return 0;
}
static void parked_checkpoint_ownership(void) {
    struct md_task_checkpoints saved=checkpoints;
    checkpoints=(struct md_task_checkpoints){0};
    struct thread t={.pid=getpid()};
    int owned=0;
    assert(!task_checkpoint_borrow(&t,&owned) && owned);
    assert(task_checkpoint_borrow(&t,&owned)==-EBUSY);
    md_task_checkpoint_restore(&checkpoints,t.pid);

    struct signal_control owner={.checkpoint_suspended=1};
    t.phase=SIGNAL_CONTROL; t.signals.control=&owner;
    uint64_t mask=UINT64_C(1)<<(SIGUSR1-1);
    unsigned char scratch[sizeof(struct md_task_copy_scratch)+sizeof(siginfo_t)];
    struct md_signal_wait_exchange page;
    struct user_pt_regs regs={.pc=0x3004,.regs={[8]=SYS_rt_sigtimedwait,[0]=(uintptr_t)&mask,[3]=8}};
    struct seccomp_data request=syscall_description(&regs);
    regs.regs[0]=regs.regs[3]=regs.regs[8]=0;
    assert(md_task_signal_wait_begin(&owner.control,parked_checkpoint_trace,t.pid,&regs,&request,
        0x1000,0x1004,NULL,&t.signals.host_blocked,NULL,NULL,scratch,(uintptr_t)scratch,sizeof(scratch))
        ==MD_SIGNAL_WAIT_RELEASE);
    assert(md_task_signal_wait_park(&owner.control,(uintptr_t)&page)==MD_SIGNAL_WAIT_PARKED);
    owner.control.boundary=MD_TASK_CALL_EXIT;
    assert(!md_task_signal_control_continue(&owner.control));
    assert(signal_control_parked(&t));
    assert(!md_task_checkpoint_suspend(&checkpoints,t.pid));
    owned=0;
    assert(!task_checkpoint_borrow(&t,&owned) && !owned);
    t.kernel_resume=PTRACE_SYSEMU;
    assert(task_checkpoint_borrow(&t,&owned)==1 && !owned);
    t.kernel_resume=PTRACE_SYSCALL;
    assert(!task_checkpoint_borrow(&t,&owned) && !owned);
    assert(md_task_checkpoint_suspend(&checkpoints,t.pid)==-EBUSY);
    t.phase=IDLE;
    assert(task_checkpoint_borrow(&t,&owned)==-EBUSY && !owned);
    md_task_checkpoint_restore(&checkpoints,t.pid);
    md_task_signal_control_dispose(&owner.control);
    md_task_checkpoints_clear(&checkpoints); checkpoints=saved;
    puts("PASS checkpoint borrowing: parked signal owner retained, other double ownership rejected");
}
static long borrowed_trace(int request,pid_t pid,void *address,void *data) {
    assert(pid==123);
    if (request==PTRACE_GET_SYSCALL_INFO) {
        *(struct ptrace_syscall_info *)data=(struct ptrace_syscall_info){.op=PTRACE_SYSCALL_INFO_EXIT};
    } else if (request==PTRACE_GETSIGMASK) *(uint64_t *)data=0;
    else if (request==PTRACE_SYSCALL) { assert(!address && !data); ++borrowed_resumes; }
    else assert(request==PTRACE_SETREGSET || request==PTRACE_SETSIGMASK);
    return 0;
}
static void cancel_stopped_child(void) {
    int channel[2]; assert(!pipe2(channel,O_CLOEXEC));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(channel[1]); char byte;
        /* EVENT_WAIT: parent cancels this exact ptraced child. */
        (void)read(channel[0],&byte,1); _exit(1);
    }
    close(channel[0]);
    assert(!ptrace(PTRACE_SEIZE,child,NULL,(void *)(uintptr_t)PTRACE_O_EXITKILL));
    assert(!ptrace(PTRACE_INTERRUPT,child,NULL,NULL));
    siginfo_t info={0};
    /* EVENT_WAIT: retain the acknowledged stop, including its wait record. */
    assert(!waitid(P_PID,child,&info,WSTOPPED|WNOWAIT|__WALL));
    assert(info.si_pid==child && info.si_code==CLD_TRAPPED);
    int fd=(int)syscall(SYS_pidfd_open,child,0); assert(fd>=0);
    assert(!md_process_signal_child(child,SIGKILL));
    int resumed=(int)ptrace(PTRACE_CONT,child,NULL,NULL);
    assert(!resumed || errno==ESRCH);
    /* EVENT_WAIT: cancellation must terminate the child, not skip its stop. */
    int ready=md_event_wait_fd(fd,POLLIN,md_event_now()+2000000000LL);
    if (ready<0) assert(!kill(child,SIGKILL));
    int status; assert(waitpid(child,&status,__WALL)==child);
    close(fd); close(channel[1]);
    assert(ready>=0 && WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
    puts("PASS child cancellation: retained ptrace stop does not hide a live owned process");
}
static void borrowed_interrupt(void) {
    struct md_task_call call={0};
    struct user_pt_regs original={.pc=0x2004};
    struct md_task_call_arguments args={.number=SYS_gettid};
    assert(!md_task_call_begin(&call,borrowed_trace,123,MD_TASK_CALL_EXIT,&original,0x1000,0x1004,&args));
    struct thread t={.pid=123,.kernel_stopped=1,.interrupt_pending=1,.listening=1};
    unsigned stage=call.stage, wakes=group_wakes;
    borrowed_resumes=0;
    int status=(PTRACE_EVENT_STOP<<16)|(SIGTRAP<<8)|0x7f;
    assert(task_call_signal(&t,&call,status));
    assert(borrowed_resumes==1 && call.stage==stage && !t.kernel_stopped && !t.interrupt_pending);
    assert(!t.listening && group_wakes==wakes+1);
    call.interrupt_pending=1; t.kernel_stopped=1; t.interrupt_pending=1;
    assert(!task_call_signal(&t,&call,status));
    assert(borrowed_resumes==1 && call.interrupt_pending && t.kernel_stopped && !t.interrupt_pending);
    assert(!task_call_signal(&t,&call,(PTRACE_EVENT_SECCOMP<<16)|(SIGTRAP<<8)|0x7f));
    call.interrupt_pending=0;
    struct retained_retirement retirement={.actor=&t,.stage=2,.reap={.call=call}};
    assert(!retained_retirement && !retained_barrier);
    retained_retirement=&retirement;
    retained_barrier=md_task_quiesce_new(borrowed_trace); assert(retained_barrier);
    assert(!md_task_quiesce_add(retained_barrier,t.pid,1,0));
    struct md_task_quiesce_stop stop;
    assert(!md_task_quiesce_borrow(retained_barrier,t.pid,&stop));
    assert(retained_barrier_event(t.pid,status,NULL)==1);
    assert(borrowed_resumes==2 && retirement.reap.call.stage==call.stage);
    assert(!md_task_quiesce_return(retained_barrier,t.pid));
    md_task_quiesce_destroy(retained_barrier); retained_barrier=NULL; retained_retirement=NULL;
    group_wakes=wakes;
    puts("PASS borrowed task interrupt: coordinator wake resumes exact owner; owned completion remains untouched");
}
static struct user_pt_regs continuation_registers;
static struct ptrace_syscall_info continuation_info;
static uint64_t continuation_mask;
static int continuation_request;
static long continuation_trace(int request,pid_t pid,void *address,void *data) {
    assert(pid==123);
    if (request==PTRACE_GET_SYSCALL_INFO)
        *(struct ptrace_syscall_info *)data=continuation_info;
    else if (request==PTRACE_GETSIGMASK) *(uint64_t *)data=continuation_mask;
    else if (request==PTRACE_SETSIGMASK) continuation_mask=*(uint64_t *)data;
    else if (request==PTRACE_GETREGSET || request==PTRACE_SETREGSET) {
        struct iovec *io=data;
        if ((uintptr_t)address==NT_PRSTATUS) {
            if (request==PTRACE_GETREGSET) memcpy(io->iov_base,&continuation_registers,sizeof(continuation_registers));
            else memcpy(&continuation_registers,io->iov_base,sizeof(continuation_registers));
        } else assert(request==PTRACE_SETREGSET && (uintptr_t)address==NT_ARM_SYSTEM_CALL);
    } else {
        assert(request==PTRACE_SYSCALL || request==PTRACE_SYSEMU);
        assert(!address && !data); continuation_request=request;
    }
    return 0;
}
static void borrowed_continuations(void) {
    for (unsigned owner=0;owner<5;++owner) {
        struct signal_control signal={.started=1};
        struct guest_notification notification={0};
        struct filter_install filter={0};
        struct md_task_copy_scratch scratch={0};
        struct thread t={.pid=123,.kernel_stopped=1,.signals.shielded=1};
        if (owner<2) {
            t.phase=SIGNAL_CONTROL; t.signals.control=&signal;
            signal.control.invoking=owner==0; signal.control.copying=owner==1;
        } else if (owner==2) {
            t.phase=GUEST_NOTIFICATION; t.seccomp.notification=&notification;
        } else {
            t.phase=FILTER_CAPTURE; t.seccomp.install=&filter;
            if (owner==3) filter.admission.stage=1;
            else filter.copy.scratch=&scratch;
        }
        struct md_task_call *call=task_call_continuation(&t); assert(call);
        *call=(struct md_task_call){0};
        continuation_registers=(struct user_pt_regs){.pc=0x2004,.regs={[8]=SYS_getpid,[0]=123}};
        struct user_pt_regs saved=continuation_registers;
        continuation_info=(struct ptrace_syscall_info){.op=PTRACE_SYSCALL_INFO_EXIT};
        continuation_mask=0;
        struct md_task_call_arguments arguments={.number=SYS_gettid};
        assert(!md_task_call_begin(call,continuation_trace,t.pid,MD_TASK_CALL_EXIT,&saved,0x1000,0x1004,&arguments));
        assert(!thread_index[t.pid%THREAD_BUCKETS]); thread_index[t.pid%THREAD_BUCKETS]=&t;
        const int expected[]={PTRACE_SYSCALL,PTRACE_SYSCALL,PTRACE_SYSEMU,PTRACE_SYSCALL};
        for (unsigned step=0;step<4;++step) {
            t.kernel_stopped=1; continuation_request=0;
            resume(t.pid,0);
            assert(continuation_request==expected[step] && !t.kernel_stopped);
            continuation_registers.pc=0x1004;
            continuation_info=(struct ptrace_syscall_info){.op=step%2
                ? PTRACE_SYSCALL_INFO_EXIT : PTRACE_SYSCALL_INFO_ENTRY};
            if (step%2) continuation_registers.regs[0]=123;
            else {
                continuation_info.entry.nr=step ? SYS_getpid : SYS_gettid;
                memcpy(continuation_info.entry.args,continuation_registers.regs,sizeof(continuation_info.entry.args));
            }
            assert(md_task_call_event(call,((SIGTRAP|0x80)<<8)|0x7f)==(step==3));
        }
        assert(!memcmp(&saved,&continuation_registers,sizeof(saved)) && !continuation_mask);
        thread_index[t.pid%THREAD_BUCKETS]=NULL;
    }
    puts("PASS borrowed continuations: signal/copy, notification and filter owners retain entry, exit and SYSEMU restoration");
}
static long pending_test_trace(int request,pid_t pid,void *address,void *data) {
    assert(request==PTRACE_PEEKSIGINFO && pid==123);
    const struct ptrace_peeksiginfo_args *args=address;
    assert(!args->flags && args->off<=pending_test_count);
    ++pending_test_calls;
    unsigned count=pending_test_count-args->off;
    if (count>(unsigned)args->nr) count=args->nr;
    for (unsigned i=0;i<count;++i)
        ((siginfo_t *)data)[i]=(siginfo_t){.si_signo=SIGRTMIN,.si_errno=args->off+i};
    return count;
}
static int pending_test_visit(void *unused,const siginfo_t *info) {
    (void)unused;
    assert(info->si_signo==SIGRTMIN && info->si_errno==(int)pending_test_seen++);
    return 0;
}
static void pending_queue_scan(void) {
    const unsigned counts[]={0,1,15,16,17,4096,32768};
    for (unsigned i=0;i<sizeof(counts)/sizeof(*counts);++i) {
        pending_test_count=counts[i]; pending_test_seen=pending_test_calls=0;
        assert(!md_task_pending_scan(pending_test_trace,123,pending_test_visit,NULL));
        assert(pending_test_seen==pending_test_count && pending_test_calls<=12);
    }
    puts("PASS pending signals: exact ordered scan and geometric kernel queue traversal");
}
static siginfo_t debug_host_signal;
static unsigned debug_host_scans;
static long debug_host_trace(int request,pid_t pid,void *address,void *data) {
    assert(request==PTRACE_PEEKSIGINFO && pid==123);
    const struct ptrace_peeksiginfo_args *args=address;
    assert(!args->flags && !args->off);
    ++debug_host_scans;
    if (!debug_host_signal.si_signo) return 0;
    *(siginfo_t *)data=debug_host_signal;
    return 1;
}
static void debug_host_exit(void) {
    struct thread t={.pid=123,.entered=1,.signals.host=1,.debug.call_active=1};
    struct user_pt_regs regs={.pc=0x12345678,.regs={[8]=SYS_setresgid,[0]=81}};
    debug_host_signal=(siginfo_t){.si_signo=SIGSYS,.si_code=SYS_SECCOMP};
    debug_host_signal.si_arch=AUDIT_ARCH_AARCH64;
    debug_host_signal.si_syscall=SYS_setresgid;
    debug_host_signal.si_call_addr=(void *)(uintptr_t)regs.pc;
    checkpoints.trace=debug_host_trace; debug_host_scans=0;
    assert(debug_host_policy_pending(&t,&regs)==1 && debug_host_scans==1);
    t.signals.host=0; assert(!debug_host_policy_pending(&t,&regs) && debug_host_scans==1);
    t.signals.host=1; t.debug.call_active=0;
    assert(!debug_host_policy_pending(&t,&regs) && debug_host_scans==1);
    t.debug.call_active=1;
    ++regs.pc; assert(!debug_host_policy_pending(&t,&regs)); --regs.pc;
    debug_host_signal.si_code=SI_QUEUE; assert(!debug_host_policy_pending(&t,&regs));
    debug_host_signal.si_code=SYS_SECCOMP;
    const uint32_t actions[]={SECCOMP_RET_ALLOW,SECCOMP_RET_LOG,SECCOMP_RET_ERRNO|EACCES,
        SECCOMP_RET_TRACE|17,SECCOMP_RET_USER_NOTIF,SECCOMP_RET_TRAP|19,SECCOMP_RET_KILL_PROCESS};
    for (unsigned i=0;i<sizeof(actions)/sizeof(*actions);++i) {
        struct sock_filter code[]={BPF_STMT(BPF_RET|BPF_K,actions[i])};
        struct sock_fprog program={1,code};
        assert(!md_seccomp_policy_capture(NULL,&program,&t.seccomp.policy));
        assert(debug_host_policy_pending(&t,&regs)==(i<5));
        md_seccomp_policy_release(t.seccomp.policy); t.seccomp.policy=NULL;
    }
    regs.regs[8]=debug_host_signal.si_syscall=SYS_getpid;
    assert(!debug_host_policy_pending(&t,&regs));
    const int optional[]={SYS_set_robust_list,SYS_get_robust_list,SYS_rseq,
        SYS_get_mempolicy,SYS_set_mempolicy,SYS_mbind,SYS_adjtimex,SYS_clock_adjtime,SYS_kcmp};
    for (unsigned i=0;i<sizeof(optional)/sizeof(*optional);++i) {
        regs.regs[8]=debug_host_signal.si_syscall=optional[i];
        assert(debug_host_policy_pending(&t,&regs)==1);
    }
    debug_host_signal.si_signo=0; assert(!debug_host_policy_pending(&t,&regs));
    checkpoints.trace=NULL;
    puts("PASS debugger host exit: deferred adapter results, guest policy precedence and native signal ordering");
}
static void signalfd_information(void) {
    sigset_t set,previous; sigemptyset(&set); sigaddset(&set,SIGSYS); sigaddset(&set,SIGUSR1);
    assert(!sigprocmask(SIG_BLOCK,&set,&previous));
    int fd=signalfd(-1,&set,SFD_CLOEXEC|SFD_NONBLOCK); assert(fd>=0);
    const int codes[]={SI_USER,SI_KERNEL,SI_QUEUE,SI_TIMER,SI_SIGIO,1,2,3,6,7,127,-64};
    for (unsigned s=0;s<2;++s) for (unsigned i=0;i<sizeof(codes)/sizeof(*codes);++i) {
        siginfo_t info={.si_signo=s ? SIGUSR1 : SIGSYS,.si_code=codes[i],.si_errno=17};
        info.si_pid=getpid(); info.si_uid=getuid(); info.si_value.sival_ptr=(void *)0x12345678UL;
        assert(!syscall(SYS_rt_sigqueueinfo,getpid(),info.si_signo,&info));
        struct signalfd_siginfo actual,expected;
        assert(read(fd,&actual,sizeof(actual))==sizeof(actual));
        md_signal_fd_info(&info,&expected);
        assert(!memcmp(&actual,&expected,sizeof(actual)));
    }
    assert(!close(fd) && !sigprocmask(SIG_SETMASK,&previous,NULL));
    puts("PASS signalfd information: native kernel layout oracle for queued, timer, poll and signal-specific codes");
}
static int pending_child_gone;
static long pending_child_trace(int request,pid_t pid,void *address,void *data) {
    assert(request==PTRACE_PEEKSIGINFO && pid==123 && address && data);
    if (pending_child_gone) { errno=ESRCH; return -1; }
    return 0;
}
static void pending_signal_ownership(void) {
    struct md_signal_pending *first=md_signal_pending_new(); assert(first);
    struct md_signal_pending *peer=md_signal_pending_fork(first,1,1); assert(peer);
    siginfo_t shared={.si_signo=SIGSYS,.si_code=SI_QUEUE,.si_errno=1};
    siginfo_t private={.si_signo=SIGSYS,.si_code=SI_QUEUE,.si_errno=2};
    assert(md_signal_pending_put(first,MD_SIGNAL_SHARED,&shared)==1);
    assert(md_signal_pending_put(peer,MD_SIGNAL_SHARED,&private)==0);
    assert(md_signal_pending_put(first,MD_SIGNAL_PRIVATE,&private)==1);
    struct md_signal_pending_value a,b;
    assert(md_signal_pending_peek(first,&a) && a.scope==MD_SIGNAL_PRIVATE && a.info.si_errno==2);
    assert(md_signal_pending_peek(peer,&b) && b.scope==MD_SIGNAL_SHARED && b.info.si_errno==1);
    struct md_signal_pending *child=md_signal_pending_fork(first,0,0); assert(child);
    struct md_signal_pending *handler_peer=md_signal_pending_fork(first,0,1); assert(handler_peer);
    siginfo_t notice={.si_signo=SIGCHLD,.si_code=CLD_EXITED};
    notice.si_pid=456; notice.si_uid=2000; notice.si_status=23;
    struct md_signal_origin origin={.info=notice,.uid=1000,.valid=1};
    *md_signal_pending_child(first,MD_SIGNAL_SHARED)=origin;
    *md_signal_pending_child(first,MD_SIGNAL_PRIVATE)=origin;
    md_signal_pending_child_changed(first);
    assert(md_signal_pending_child_dirty(peer) && !md_signal_pending_child_dirty(child));
    assert(!md_signal_pending_origin(child,&notice) && !md_signal_pending_origin(handler_peer,&notice));
    assert(md_signal_pending_origin(peer,&notice)->uid==1000);
    md_signal_pending_consumed(first,&notice);
    assert(!md_signal_pending_child(first,MD_SIGNAL_PRIVATE)->valid);
    assert(md_signal_pending_origin(first,&notice)==md_signal_pending_child(peer,MD_SIGNAL_SHARED));
    assert(!md_signal_pending_exec(peer) && md_signal_pending_origin(peer,&notice)->uid==1000);
    assert(md_signal_pending_domain(first)==md_signal_pending_domain(handler_peer));
    assert(md_signal_pending_domain(first)!=md_signal_pending_domain(child));
    assert(!md_signal_pending_peek(handler_peer,&a));
    assert(!md_signal_pending_exec(handler_peer));
    assert(md_signal_pending_domain(first)!=md_signal_pending_domain(handler_peer));
    md_signal_pending_release(handler_peer);
    uint64_t domain=md_signal_pending_domain(first);
    assert(!md_signal_pending_exec(first) && md_signal_pending_domain(first)==domain);
    assert(md_signal_pending_peek(first,&a));
    struct md_signal_pending_value empty;
    assert(!md_signal_pending_peek(child,&empty));
    assert(md_signal_pending_take(peer,&b) && !md_signal_pending_take(first,&b));
    assert(md_signal_pending_put(first,MD_SIGNAL_SHARED,&private)==1);
    assert(!md_signal_pending_take(peer,&b));
    assert(md_signal_pending_take(first,&a));
    md_signal_pending_release(first);
    assert(md_signal_pending_peek(peer,&b) && b.info.si_errno==2);
    md_signal_pending_clear(peer,1);
    assert(!md_signal_pending_peek(peer,&b));
    assert(md_signal_pending_origin(peer,&notice)->uid==1000);
    md_signal_pending_consumed(peer,&notice);
    assert(!md_signal_pending_origin(peer,&notice));
    struct thread t={.pid=123,.signals.pending=peer};
    int old_collection=pid_collection_pending;
    checkpoints.trace=pending_child_trace;
    int stopped=(PTRACE_EVENT_STOP<<16) | (SIGTRAP<<8) | 0x7f;
    pending_child_gone=1;
    child_signals_observe(&t,stopped);
    assert(md_signal_pending_child_dirty(peer));
    pending_child_gone=0;
    child_signals_observe(&t,stopped);
    assert(!md_signal_pending_child_dirty(peer) && pid_collection_pending);
    /* No pending-signal query may be issued against a running shared caller. */
    checkpoints.trace=NULL;
    process_reap(&t,456);
    assert(md_signal_pending_child_dirty(peer));
    checkpoints.trace=pending_child_trace;
    child_signals_observe(&t,stopped);
    assert(!md_signal_pending_child_dirty(peer));
    checkpoints.trace=NULL; pid_collection_pending=old_collection;
    md_signal_pending_release(peer); md_signal_pending_release(child);
    puts("PASS pending signal ownership: shared/private scope, first-info coalescing, fork, exit and consumption tokens");
}

static int external_signal_code;
static uint64_t shield_test_mask;
static struct user_pt_regs transfer_restored;
static unsigned transfer_completions;
static int transfer_result;
static long transfer_test_trace(int request,pid_t pid,void *address,void *data) {
    assert(request==PTRACE_SETREGSET && pid==123 && (uintptr_t)address==NT_PRSTATUS);
    struct iovec *iov=data; assert(iov->iov_len==sizeof(transfer_restored));
    memcpy(&transfer_restored,iov->iov_base,sizeof(transfer_restored));
    return 0;
}
static void transfer_test_complete(struct thread *t,int error) {
    assert(t->phase==DISPATCHING && !t->transfer);
    ++transfer_completions; transfer_result=error;
}
static void typed_transfer_ownership(void) {
    struct thread t={.pid=123,.memory=md_memory_new()}; assert(t.memory);
    assert(!md_memory_buffers_prepare(t.memory,1,sizeof(struct md_guest_transfer),0,1));
    thread_index[t.pid%THREAD_BUCKETS]=&t; checkpoints.trace=transfer_test_trace;
    struct user_pt_regs saved={.pc=0x1234,.sp=0x8000,.regs={1,2,3,4,5,6}};
    transfer_completions=0;
    for (unsigned mode=0;mode<5;++mode) {
        unsigned char output[32]; memset(output,0xa5,sizeof(output));
        struct supervisor_transfer *copy=calloc(1,sizeof(*copy)); assert(copy);
        *copy=(struct supervisor_transfer){.saved=saved,.previous=DISPATCHING,.data=output,
            .size=sizeof(output),.kind=mode==2 || mode==3 ? MD_TRANSFER_STRING : MD_TRANSFER_READ,
            .complete=transfer_test_complete};
        assert(!md_buffers_take_reserved(md_memory_buffers(t.memory),&copy->buffer));
        struct md_buffer_span span; assert(!md_buffer_span(&copy->buffer,&span));
        struct md_guest_transfer *frame=span.data;
        memset(frame->data,0x77,sizeof(output));
        if (mode==2) memcpy(frame->data,"path",5);
        t.transfer=copy; t.phase=TRANSFERRING;
        if (mode==4) {
            retry_wait(&t,RETRY_TRANSFER);
            assert(t.retries[0].node.list && !t.retries[1].node.list);
            transfer_discard(&t);
            assert(!t.transfer && !t.retries[0].node.list && transfer_completions==4);
        } else {
            struct user_pt_regs returned={.regs={mode==1 ? (uint64_t)-EFAULT : 0}};
            assert(transfer_checkpoint(&t,DONE,&returned)==1);
            assert(transfer_completions==mode+1 && !memcmp(&saved,&transfer_restored,sizeof(saved)));
            assert(transfer_result==(mode==1 ? -EFAULT : mode==3 ? -ENAMETOOLONG : 0));
            if (!mode) assert(output[0]==0x77 && output[31]==0x77);
            else if (mode==2) assert(!strcmp((char *)output,"path") && output[5]==0xa5);
            else assert(output[0]==0xa5 && output[31]==0xa5);
        }
        struct md_interception_buffer reused={0};
        assert(!md_buffers_take_reserved(md_memory_buffers(t.memory),&reused));
        assert(!md_buffer_release(&reused));
    }
    md_memory_release(t.memory); thread_index[t.pid%THREAD_BUCKETS]=NULL;
    checkpoints.trace=checkpoint_native_trace;
    puts("PASS typed maintenance transfer: nested continuation, bounded strings, fault, cancellation and buffer reuse");
}
static long shield_test_trace(int request,pid_t pid,void *address,void *data) {
    assert(request==PTRACE_SETSIGMASK && pid==123 && (uintptr_t)address==sizeof(uint64_t));
    shield_test_mask=*(uint64_t *)data;
    return 0;
}
static void signal_shield_ownership(void) {
    struct thread t={.pid=123,.execution.mask=MD_SIGNAL_SYS_BIT};
    checkpoints.trace=shield_test_trace;
    for (int app=0;app<2;++app) {
        t.signals.host=app;
        assert(shield(&t) && t.signals.shielded);
        assert(!!(shield_test_mask&MD_SIGNAL_SYS_BIT)==!app);
        assert(shield_test_mask&(UINT64_C(1)<<(SIGUSR1-1)));
        assert(restore_mask(&t) && !t.signals.shielded && shield_test_mask==t.execution.mask);
    }
    checkpoints.trace=NULL;
    puts("PASS signal shield: native pending SIGSYS retained, app gate available, guest mask restored");
}
static void metadata_mask_mutation(void) {
    struct thread t={.pid=123,.phase=IDLE,.ready=1,.entered=1,.memory=md_memory_new()};
    assert(t.memory);
    const uint64_t inputs[]={0x1000,UINT64_C(0x100000000)};
    const int actions[]={SIG_BLOCK,SIG_UNBLOCK,SIG_SETMASK};
    for (unsigned host=0;host<2;++host) for (unsigned i=0;i<2;++i)
            for (unsigned j=0;j<sizeof(actions)/sizeof(*actions);++j) {
        t.signals.host=host;
        struct md_task_signal_stop stop={.has_info=1,.has_regs=1,
            .info={.si_signo=SIGALRM,.si_code=SI_KERNEL},.regs={.pc=0x400004}};
        stop.regs.regs[8]=SYS_rt_sigprocmask;
        stop.regs.regs[0]=actions[j]; stop.regs.regs[1]=inputs[i];
        stop.regs.regs[2]=0x2000; stop.regs.regs[3]=8;
        assert(!interrupted_metadata_event(&t,(SIGALRM<<8)|0x7f,NULL,&stop));
        assert(!t.metadata_restart && !t.broker_event.held && !t.signals.shielded);
    }
    md_memory_release(t.memory);
    puts("PASS metadata admission: signal-mask mutations retain their native completion owner");
}
static unsigned metadata_replay_step;
static uint64_t metadata_replay_mask;
static long metadata_replay_value;
static struct user_pt_regs metadata_replay_saved;
static long metadata_replay_trace(int request,pid_t pid,void *address,void *data) {
    assert(pid==123);
    unsigned step=metadata_replay_step++;
    if (step==0 || step==5) {
        assert(request==PTRACE_GETREGSET && (uintptr_t)address==NT_PRSTATUS);
        struct user_pt_regs *regs=((struct iovec *)data)->iov_base;
        *regs=metadata_replay_saved;
        regs->pc=step ? metadata_replay_saved.pc+4 : RAW_GATE;
        if (step) regs->regs[0]=(unsigned long)metadata_replay_value;
    } else if (step==1 || step==6) {
        assert(request==PTRACE_GET_SYSCALL_INFO);
        struct ptrace_syscall_info *info=data;
        *info=(struct ptrace_syscall_info){.op=step==1 ? PTRACE_SYSCALL_INFO_ENTRY : PTRACE_SYSCALL_INFO_EXIT,
            .instruction_pointer=step==1 ? RAW_GATE : metadata_replay_saved.pc+4};
        if (step==1) {
            info->entry.nr=metadata_replay_saved.regs[8];
            memcpy(info->entry.args,metadata_replay_saved.regs,sizeof(info->entry.args));
        } else info->exit.rval=metadata_replay_value;
    } else if (step==2 || step==8) {
        assert(request==PTRACE_SETSIGMASK && (uintptr_t)address==sizeof(uint64_t));
        uint64_t expected=step==2
            ? (md_signal_blockable_mask()&~MD_SIGNAL_SYS_BIT)|(metadata_replay_mask&MD_SIGNAL_SYS_BIT)
            : metadata_replay_mask;
        assert(*(uint64_t *)data==expected);
    } else if (step==3 || step==7) {
        assert(request==PTRACE_SETREGSET && (uintptr_t)address==NT_PRSTATUS);
        struct user_pt_regs expected=metadata_replay_saved; expected.pc+=4;
        if (step==7) expected.regs[0]=(unsigned long)metadata_replay_value;
        assert(!memcmp(((struct iovec *)data)->iov_base,&expected,sizeof(expected)));
    } else {
        assert(step==4 || step==9);
        assert(request==(step==4 ? PTRACE_SYSCALL : PTRACE_CONT) && !address && !data);
    }
    return 0;
}
static void metadata_replay_completion(void) {
    debugger=md_debugger_create(&(struct md_debugger_host){0}); assert(debugger);
    const long values[]={0,-EACCES,-3};
    for (unsigned host=0;host<2;++host) for (unsigned blocked=0;blocked<2;++blocked)
            for (unsigned i=0;i<sizeof(values)/sizeof(*values);++i) {
        struct thread t={.pid=123,.phase=IDLE,.kernel_stopped=1,.signals.host=host,
            .memory=md_memory_new(),.metadata_restart=calloc(1,sizeof(struct metadata_restart))};
        assert(t.memory && t.metadata_restart);
        metadata_replay_saved=(struct user_pt_regs){.pc=0x123400,.sp=0x567800,.pstate=0x60000000};
        for (unsigned n=0;n<31;++n) metadata_replay_saved.regs[n]=0x112233440000+n;
        metadata_replay_saved.regs[0]=(unsigned long)-3; metadata_replay_saved.regs[8]=SYS_fstat;
        metadata_replay_mask=(UINT64_C(1)<<(SIGUSR2-1))|(blocked ? MD_SIGNAL_SYS_BIT : 0);
        metadata_replay_value=values[i]; metadata_replay_step=0;
        *t.metadata_restart=(struct metadata_restart){.saved=metadata_replay_saved,
            .stage=METADATA_ENTRY,.mask=metadata_replay_mask,.requeued_signal=SIGALRM};
        t.broker_event.held=t.broker_event.has_info=1;
        thread_index[t.pid%THREAD_BUCKETS]=&t; checkpoints.trace=metadata_replay_trace;
        int status=((SIGTRAP|0x80)<<8)|0x7f;
        assert(metadata_restart_event(&t,status) && metadata_replay_step==5);
        assert(t.metadata_restart->stage==METADATA_WAIT && t.signals.shielded);
        assert(metadata_restart_event(&t,status) && metadata_replay_step==10);
        assert(!t.metadata_restart && !t.broker_event.held && !t.signals.shielded
            && !t.kernel_stopped && t.metadata_signal_requeued==SIGALRM && t.phase==IDLE);
        checkpoints.trace=checkpoint_native_trace; thread_index[t.pid%THREAD_BUCKETS]=NULL;
        md_memory_release(t.memory);
    }
    md_debugger_destroy(debugger); debugger=NULL;
    puts("PASS metadata replay: native policy mask, original registers and direct exit without service execution");
}
extern void test_wait_svc(void);
static volatile sig_atomic_t replay_traps,replay_alarms;
static void replay_signal(int signal,siginfo_t *info,void *context) {
    if (signal==SIGALRM) { ++replay_alarms; return; }
    ucontext_t *frame=context;
    assert(signal==SIGSYS && info->si_syscall==SYS_fstat);
    assert(frame->uc_mcontext.regs[0]==(unsigned long)-3);
    frame->uc_mcontext.regs[0]=(unsigned long)-EACCES;
    ++replay_traps;
}
static void metadata_policy_delivery(void) {
    replay_traps=replay_alarms=0;
    int channel[2]; assert(!socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,channel));
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        close(channel[0]);
        struct sigaction action={.sa_sigaction=replay_signal,.sa_flags=SA_SIGINFO};
        assert(!sigaction(SIGSYS,&action,NULL) && !sigaction(SIGALRM,&action,NULL));
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_fstat,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRAP),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getpid,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog filter={sizeof(code)/sizeof(*code),code};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&filter));
        char byte='r'; assert(write(channel[1],&byte,1)==1);
        /* EVENT_WAIT: parent installs the replay at an acknowledged stop. */
        (void)read(channel[1],&byte,1);
        (void)syscall(SYS_getpid);
        _exit(replay_traps!=1 || replay_alarms!=1);
    }
    close(channel[1]); char byte;
    assert(read(channel[0],&byte,1)==1);
    assert(!ptrace(PTRACE_SEIZE,pid,NULL,(void *)(uintptr_t)
        (PTRACE_O_EXITKILL|PTRACE_O_TRACESYSGOOD|PTRACE_O_TRACESECCOMP)));
    assert(!ptrace(PTRACE_INTERRUPT,pid,NULL,NULL));
    int status;
    assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status));
    assert(write(channel[0],&byte,1)==1); close(channel[0]);
    assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
    assert(waitpid(pid,&status,0)==pid && (unsigned)status>>16==PTRACE_EVENT_SECCOMP);
    debugger=md_debugger_create(&(struct md_debugger_host){0}); assert(debugger);
    struct thread t={.pid=pid,.tgid=pid,.kernel_stopped=1,.memory=md_memory_new(),
        .metadata_restart=calloc(1,sizeof(struct metadata_restart))};
    assert(t.memory && t.metadata_restart && !thread_index[(unsigned)pid%THREAD_BUCKETS]);
    thread_index[(unsigned)pid%THREAD_BUCKETS]=&t;
    assert(skip(pid));
    assert(!ptrace(PTRACE_SYSCALL,pid,NULL,NULL));
    assert(waitpid(pid,&status,0)==pid && WSTOPSIG(status)==(SIGTRAP|0x80));
    struct metadata_restart *r=t.metadata_restart;
    assert(registers(pid,&r->saved));
    uintptr_t return_pc=r->saved.pc; r->saved.pc-=4;
    r->saved.regs[0]=(unsigned long)-3; r->saved.regs[8]=SYS_fstat;
    assert(save_signal_mask(&t,&r->mask,"test-replay-mask"));
    siginfo_t pending={.si_signo=SIGALRM,.si_code=SI_KERNEL};
    assert(!md_signal_delivery_hold(&t.signals.delivery,&pending));
    t.broker_event.held=t.broker_event.has_info=1;
    uintptr_t gate=abi.raw_gate; abi.raw_gate=(uintptr_t)test_wait_svc+4;
    assert(shield(&t));
    metadata_restart_enter(&t);
    while (waitpid(pid,&status,0)==pid && WIFSTOPPED(status)) {
        t.kernel_stopped=1; t.interrupt_pending=0;
        md_signal_delivery_checkpoint(&t.signals.delivery,status);
        if ((unsigned)status>>16==PTRACE_EVENT_STOP) { resume(pid,0); continue; }
        if (metadata_restart_event(&t,status)) continue;
        int signal=WSTOPSIG(status),deliver=signal;
        int owned=md_signal_delivery_event(&t.signals.delivery,pid,signal,&deliver);
        assert(owned>=0);
        if (signal==SIGSYS) {
            struct user_pt_regs regs; assert(registers(pid,&regs));
            assert(regs.pc==return_pc && regs.regs[0]==(unsigned long)-3);
            siginfo_t info; assert(!ptrace(PTRACE_GETSIGINFO,pid,NULL,&info));
            assert(!md_signal_delivery_before_handler(&t.signals.delivery,pid,&info));
        }
        resume(pid,deliver);
    }
    assert(WIFEXITED(status) && !WEXITSTATUS(status));
    abi.raw_gate=gate; md_signal_delivery_clear(&t.signals.delivery); md_memory_release(t.memory);
    thread_index[(unsigned)pid%THREAD_BUCKETS]=NULL;
    md_debugger_destroy(debugger); debugger=NULL;
    puts("PASS metadata replay: pending policy signal retains original call before asynchronous delivery");
}
static long external_signal_trace(int request,pid_t pid,void *address,void *data) {
    (void)address;
    assert(request==PTRACE_GETSIGINFO && pid==123);
    *(siginfo_t *)data=(siginfo_t){.si_signo=SIGSYS,.si_code=external_signal_code,.si_errno=19};
    find_thread(pid)->resume_lost=1;
    return 0;
}
static void external_signal_target(void) {
    struct md_signal_pending *pending=md_signal_pending_new(); assert(pending);
    struct md_signal_pending *peer=md_signal_pending_fork(pending,1,1); assert(peer);
    struct thread t={.pid=123,.signals.host=1,.entered=1,.signals.host_blocked=1,.signals.pending=pending};
    thread_index[t.pid%THREAD_BUCKETS]=&t; checkpoints.trace=external_signal_trace;
    const int codes[]={SI_USER,SI_TKILL,SI_QUEUE,SI_TIMER};
    for (unsigned i=0;i<sizeof(codes)/sizeof(*codes);++i) {
        t.resume_lost=0; external_signal_code=codes[i];
        struct md_task_signal_stop stop;
        md_task_signal_stop_init(&stop,native_trace);
        assert(host_policy_signal(&t,&stop));
        struct md_signal_pending_value value;
        assert(!md_signal_pending_peek(peer,&value));
        assert(md_signal_pending_peek(pending,&value) && value.scope==MD_SIGNAL_PRIVATE);
        assert(value.info.si_code==codes[i] && value.info.si_errno==19);
        assert(md_signal_pending_take(pending,&value));
    }
    checkpoints.trace=NULL; thread_index[t.pid%THREAD_BUCKETS]=NULL;
    md_signal_pending_release(peer); md_signal_pending_release(pending);
    puts("PASS external signal delivery: retained kernel-selected task, independent of siginfo source code");
}
static unsigned policy_info_reads,policy_register_reads,policy_info_writes;
static long policy_signal_trace(int request,pid_t pid,void *address,void *data) {
    assert(pid==123);
    if (request==PTRACE_GETSIGINFO) {
        ++policy_info_reads;
        siginfo_t info={.si_signo=SIGSYS,.si_code=SYS_SECCOMP,.si_errno=19};
        info.si_syscall=SYS_getpid; info.si_arch=AUDIT_ARCH_AARCH64;
        info.si_call_addr=(void *)0x1234; *(siginfo_t *)data=info;
    } else if (request==PTRACE_GETREGSET) {
        assert((uintptr_t)address==NT_PRSTATUS); ++policy_register_reads;
        *(struct user_pt_regs *)((struct iovec *)data)->iov_base=(struct user_pt_regs){.pc=0x1234};
    } else { assert(request==PTRACE_SETSIGINFO); ++policy_info_writes; }
    return 0;
}
static void policy_signal_observation(void) {
    struct thread t={.pid=123,.phase=IDLE,.entered=1,.signals.actions=md_signal_actions_new()};
    assert(t.signals.actions);
    struct md_kernel_action action={.handler=0x5678};
    assert(!md_signal_actions_commit(t.signals.actions,SIGSYS,&action));
    checkpoints.trace=policy_signal_trace;
    for (unsigned cookie=19;cookie<21;++cookie) {
        struct sock_filter code[]={BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRAP|cookie)};
        struct sock_fprog program={1,code};
        assert(!md_seccomp_policy_capture(NULL,&program,&t.seccomp.policy));
        struct md_task_signal_stop stop;
        md_task_signal_stop_init(&stop,native_trace);
        policy_info_reads=policy_register_reads=policy_info_writes=0;
        t.signals.host=0;
        assert(!host_policy_signal(&t,&stop));
        assert(!policy_info_reads && !policy_register_reads && !policy_info_writes);
        t.signals.host=1;
        assert(signal_observe(&t,&stop,1));
        assert(!host_policy_signal(&t,&stop) && stop.info.si_errno==(int)cookie);
        assert(signal_observe(&t,&stop,1));
        assert(signal_replace_info(&t,&stop,&stop.info));
        assert(policy_info_reads==1 && policy_register_reads==1 && policy_info_writes==(cookie!=19));
        md_seccomp_policy_release(t.seccomp.policy); t.seccomp.policy=NULL;
    }
    checkpoints.trace=checkpoint_native_trace;
    md_signal_actions_release(t.signals.actions);
    puts("PASS policy signal observation: one native snapshot, exact changed siginfo, no host gate for shell");
}
static void trace_policy_admission(void) {
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_newfstatat,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_SIGNAL),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    struct thread t={.signals.host=1,.entered=1,.phase=IDLE};
    assert(!md_seccomp_policy_capture(NULL,&program,&t.seccomp.policy));
    struct seccomp_data call={.arch=AUDIT_ARCH_AARCH64,.nr=SYS_newfstatat,.instruction_pointer=0x12345678};
    assert(guest_trace_rejected(&t,&call));
    call.nr=SYS_openat; assert(!guest_trace_rejected(&t,&call));
    call.nr=SYS_newfstatat; call.instruction_pointer=RAW_GATE;
    assert(!guest_trace_rejected(&t,&call));
    call.instruction_pointer=abi.task_return; assert(!guest_trace_rejected(&t,&call));
    uintptr_t begin=abi.code_begin,end=abi.code_end;
    abi.code_begin=0x20000; abi.code_end=0x30000;
    for (uintptr_t pc=abi.code_begin;pc<abi.code_end;pc+=0x1000) {
        call.instruction_pointer=pc;
        assert(!guest_policy_boundary(&t,&call) && !guest_trace_rejected(&t,&call));
    }
    call.instruction_pointer=abi.code_end;
    assert(guest_policy_boundary(&t,&call) && guest_trace_rejected(&t,&call));
    abi.code_begin=begin; abi.code_end=end;
    call.instruction_pointer=0x12345678;
    t.phase=DISPATCHING; assert(!guest_trace_rejected(&t,&call));
    t.phase=IDLE; t.signals.host=0; assert(guest_trace_rejected(&t,&call));
    t.signals.host=1; t.entered=0; assert(!guest_trace_rejected(&t,&call));
    md_seccomp_policy_release(t.seccomp.policy);
    puts("PASS original TRACE admission: notification calls, selector collision and owned-gate isolation");
}

static void filter_result(struct thread *caller,long result) {
    execution_capture(caller,&caller->execution.registers);
    struct user_pt_regs regs=caller->execution.registers;
    regs.regs[0]=result;
    assert(!filter_observed(caller,&regs));
}
static void filter_publication(void) {
    struct thread *saved=threads;
    struct thread outside={.pid=3,.tgid=3};
    struct thread retired={.pid=0,.tgid=1,.next=&outside};
    struct thread peer={.pid=2,.tgid=1,.next=&retired};
    struct thread caller={.pid=1,.tgid=1,.entered=1,.next=&peer};
    threads=&caller;
    caller.execution.registers.regs[8]=SYS_seccomp;
    caller.execution.registers.regs[0]=SECCOMP_SET_MODE_FILTER;
    caller.execution.registers.regs[1]=SECCOMP_FILTER_FLAG_TSYNC;
    filter_result(&caller,2);
    assert(!caller.seccomp.application_filter && !peer.seccomp.application_filter);
    filter_result(&caller,-EINVAL);
    assert(!caller.seccomp.application_filter && !peer.seccomp.application_filter);
    filter_result(&caller,0);
    assert(caller.seccomp.application_filter && peer.seccomp.application_filter);
    assert(!outside.seccomp.application_filter && !retired.seccomp.application_filter);
    caller.seccomp.application_filter=peer.seccomp.application_filter=0;
    caller.execution.registers.regs[1]=SECCOMP_FILTER_FLAG_NEW_LISTENER;
    filter_result(&caller,5);
    assert(caller.seccomp.application_filter && !peer.seccomp.application_filter);
    caller.seccomp.application_filter=0;
    filter_result(&caller,0);
    assert(caller.seccomp.application_filter && !peer.seccomp.application_filter);
    caller.seccomp.application_filter=0; caller.entered=0;
    filter_result(&caller,5); assert(!caller.seccomp.application_filter);
    caller.entered=1; caller.execution.registers.regs[8]=SYS_prctl;
    caller.execution.registers.regs[0]=PR_SET_SECCOMP; caller.execution.registers.regs[1]=SECCOMP_MODE_STRICT;
    filter_result(&caller,0); assert(!caller.seccomp.application_filter);
    caller.execution.registers.regs[1]=SECCOMP_MODE_FILTER;
    filter_result(&caller,0); assert(caller.seccomp.application_filter && !peer.seccomp.application_filter);
    struct md_interception_buffers *pool=NULL;
    assert(!md_buffers_create_readonly(1,sizeof(struct md_seccomp_image),&pool));
    const long outcomes[]={-EINVAL,23,0};
    for (unsigned i=0;i<sizeof(outcomes)/sizeof(*outcomes);++i) {
        caller.seccomp.application_filter=peer.seccomp.application_filter=0;
        caller.execution.registers.regs[8]=SYS_seccomp;
        caller.execution.registers.regs[0]=SECCOMP_SET_MODE_FILTER;
        caller.execution.registers.regs[1]=SECCOMP_FILTER_FLAG_TSYNC;
        caller.seccomp.install=calloc(1,sizeof(*caller.seccomp.install)); assert(caller.seccomp.install);
        struct sock_filter code[]={BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES)};
        struct sock_fprog program={1,code};
        assert(!md_seccomp_capture_prepare(&caller.seccomp.install->capture,pool,0x100000,
            NULL,&program,NULL,0,0,NULL));
        policy_admission_retain(&peer,1);
        filter_result(&caller,outcomes[i]);
        assert(!caller.seccomp.install);
        assert(!!caller.seccomp.policy==(outcomes[i]==0) && caller.seccomp.policy==peer.seccomp.policy);
        assert(!outside.seccomp.policy && !retired.seccomp.policy);
        uint32_t admitted;
        assert(peer.seccomp.admission_policy_valid && peer.seccomp.admission_policy_pending);
        assert(!md_seccomp_policy_eval(admission_policy(&peer),&(struct seccomp_data){.nr=SYS_mount},&admitted));
        assert(admitted==SECCOMP_RET_ALLOW);
        md_seccomp_policy_release(peer.seccomp.admission_policy); peer.seccomp.admission_policy=NULL;
        peer.seccomp.admission_policy_valid=peer.seccomp.admission_policy_pending=0;
        if (caller.seccomp.policy) {
            uint32_t decision;
            assert(!md_seccomp_policy_eval(caller.seccomp.policy,&(struct seccomp_data){.nr=SYS_mount},&decision));
            assert(decision==(SECCOMP_RET_ERRNO|EACCES));
            md_seccomp_policy_replace(&caller.seccomp.policy,NULL);
            md_seccomp_policy_replace(&peer.seccomp.policy,NULL);
        }
        struct md_interception_buffer loan={0};
        assert(!md_buffers_take(pool,&loan) && !md_buffer_release(&loan));
    }
    md_buffers_release(pool);
    threads=saved;
    puts("PASS filter publication: captured chain, slot retirement, listener results, failed TSYNC, exact peer scope and bootstrap isolation");
}
static void filter_birth_order(void) {
    const long numbers[]={SYS_clone,SYS_clone3,SYS_execve,SYS_execveat};
    for (unsigned i=0;i<sizeof(numbers)/sizeof(*numbers);++i) {
        struct thread t={.pid=123,.phase=IDLE,.memory=md_memory_new()}; assert(t.memory);
        t.resources.files=md_fd_origins_new(); assert(t.resources.files);
        assert(!md_memory_readonly_prepare(t.memory,1,sizeof(struct md_seccomp_image)));
        struct md_resource_order install={0};
        assert(md_memory_order_begin(t.memory,&install,NULL)==1);
        assert(!md_resource_order_start(&install));
        struct user_pt_regs registers={0}; registers.regs[8]=numbers[i];
        struct seccomp_data call=syscall_description(&registers);
        fd_begin(&t,&call,&registers,MD_INTERCEPT_FD);
        assert(md_resource_order_state(&t.descriptors.policy_order)==MD_RESOURCE_ORDER_QUEUED);
        assert(!t.descriptors.order.table && !debugger_at_boundary(&t) && !entry_fence_boundary(&t));
        assert(!memcmp(&t.descriptors.registers,&registers,sizeof(registers)));
        fd_discard(&t);
        assert(!t.descriptors.policy_order.queue && debugger_at_boundary(&t));
        assert(!md_resource_order_complete(&install));
        md_fd_origins_release(t.resources.files); md_memory_release(t.memory);
    }
    puts("PASS filter/birth ordering: clone, clone3 and exec wait before FD ownership; cancellation releases the exact turn");
}
static struct md_resource_order filter_replay_waiter;
static long filter_replay_trace(int request,pid_t pid,void *address,void *data) {
    assert(request==PTRACE_SETREGSET && pid==123 && (uintptr_t)address==NT_PRSTATUS && data);
    assert(!find_thread(pid)->seccomp.install);
    assert(md_resource_order_state(&filter_replay_waiter)==MD_RESOURCE_ORDER_READY);
    /* Stop at the first kernel publication, before restoring the signal mask. */
    errno=ESRCH; return -1;
}
static void filter_replay_ownership(void) {
    const long outcomes[]={0,-ENOMEM,-EINVAL};
    for (unsigned i=0;i<sizeof(outcomes)/sizeof(*outcomes);++i) {
        struct thread t={.pid=123,.phase=DISPATCHING,.seccomp.preflight=1,.memory=md_memory_new()};
        assert(t.memory);
        t.execution.request.nr=SYS_seccomp; t.execution.request.instruction_pointer=0x1000;
        t.seccomp.install=calloc(1,sizeof(*t.seccomp.install)); assert(t.seccomp.install);
        t.seccomp.install->source=-1;
        assert(md_memory_order_begin(t.memory,&t.seccomp.install->memory,NULL)==1);
        assert(!md_resource_order_start(&t.seccomp.install->memory));
        assert(!md_memory_order_begin(t.memory,&filter_replay_waiter,NULL));
        checkpoints.trace=filter_replay_trace; thread_index[t.pid%THREAD_BUCKETS]=&t;
        dispatch_complete(&t,outcomes[i]);
        assert(t.resume_lost && !t.seccomp.install && !t.seccomp.preflight && t.entry.registration_revoked);
        assert(!md_resource_order_start(&filter_replay_waiter));
        assert(!md_resource_order_complete(&filter_replay_waiter));
        thread_index[t.pid%THREAD_BUCKETS]=NULL; checkpoints.trace=checkpoint_native_trace;
        md_memory_release(t.memory);
    }
    puts("PASS filter replay: preparation releases memory ownership before application signals or repeated entry");
}
static void filter_listener_ownership(void) {
    const long outcomes[]={-EINVAL,0,5};
    for (unsigned i=0;i<sizeof(outcomes)/sizeof(*outcomes);++i) {
        struct thread caller={.pid=1,.tgid=1,.entered=1};
        caller.execution.registers.regs[8]=SYS_seccomp;
        caller.execution.registers.regs[0]=SECCOMP_SET_MODE_FILTER;
        caller.execution.registers.regs[1]=SECCOMP_FILTER_FLAG_NEW_LISTENER;
        execution_capture(&caller,&caller.execution.registers);
        caller.resources.files=md_fd_origins_new(); assert(caller.resources.files);
        caller.seccomp.install=calloc(1,sizeof(*caller.seccomp.install)); assert(caller.seccomp.install);
        caller.seccomp.install->virtual_listener=1; caller.seccomp.install->source=-1;
        struct md_interception_buffers *pool=NULL;
        assert(!md_buffers_create_readonly(1,sizeof(struct md_seccomp_image),&pool));
        struct sock_filter code[]={BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_USER_NOTIF)};
        struct sock_fprog program={1,code};
        assert(!md_seccomp_capture_prepare(&caller.seccomp.install->capture,pool,0x100000,
            NULL,&program,NULL,0,0,NULL));
        struct md_fd_order previous={0};
        assert(md_fd_order_begin(caller.resources.files,&previous,NULL)==1);
        assert(!md_fd_order_start(&previous));
        assert(filter_listener_prepare(&caller)==-EAGAIN);
        assert(caller.seccomp.install->capture.policy && !caller.seccomp.install->listener.table);
        assert(!md_fd_order_complete(&previous));
        assert(!filter_listener_prepare(&caller));
        assert(!filter_listener_prepare(&caller));
        assert(md_fd_order_state(&caller.seccomp.install->files)==MD_RESOURCE_ORDER_RUNNING);
        if (outcomes[i]>=0) {
            caller.seccomp.install->loan->number=outcomes[i];
            caller.seccomp.install->admitted=1;
            filter_result(&caller,outcomes[i]);
        } else filter_install_discard(&caller);
        assert(!caller.seccomp.install);
        struct md_fd_object *object=md_fd_origins_object(caller.resources.files,(int)outcomes[i],NULL);
        assert(!!object==(outcomes[i]>=0));
        if (object) {
            assert(md_seccomp_listener_policy(object)==caller.seccomp.policy);
            struct md_fd_origins *copy=md_fd_origins_fork(caller.resources.files,0); assert(copy);
            struct md_fd_object *forked=md_fd_origins_object(copy,(int)outcomes[i],NULL);
            assert(forked==object); md_fd_object_release(forked);
            /* The ordinary SCM_RIGHTS publication preserves the exact object. */
            struct md_fd_origins *destination=md_fd_origins_new(); assert(destination);
            struct md_fd_origin_update update={0};
            assert(!md_fd_origins_prepare_batch(destination,&object,FD_CLOEXEC,1,&update));
            int number=37;
            assert(!md_fd_origins_finish_batch(&update,1,&number,1));
            assert(!md_fd_origins_close_range(caller.resources.files,0,UINT_MAX,0));
            md_fd_origins_release(copy);
            struct md_fd_object *received=md_fd_origins_object(destination,number,NULL);
            assert(received==object);
            md_fd_object_release(received);
            struct md_fd_object *other=md_seccomp_listener_new(caller.seccomp.policy); assert(other && other!=object);
            assert(!md_fd_origins_prepare_object(destination,other,FD_CLOEXEC,&update));
            md_fd_origins_finish(&update,number);
            received=md_fd_origins_object(destination,number,NULL);
            assert(received==other && received!=object);
            md_fd_object_release(received); md_fd_object_release(other);
            md_fd_origins_release(destination);
            md_seccomp_policy_replace(&caller.seccomp.policy,NULL);
            uint32_t action;
            assert(!md_seccomp_policy_eval(md_seccomp_listener_policy(object),&(struct seccomp_data){0},&action)
                && action==SECCOMP_RET_USER_NOTIF);
        }
        md_fd_object_release(object); md_seccomp_policy_replace(&caller.seccomp.policy,NULL);
        md_fd_origins_release(caller.resources.files); md_buffers_release(pool);
    }
    puts("PASS listener ownership: ordered publication, FD zero, failure, fork, SCM_RIGHTS and descriptor reuse");
}
static void process_snapshot(void) {
    const char *public_dentries[]={"/proc/123", "/proc/123/exe", "/proc/123/cwd", "/proc/123/root",
        "/proc/123/mem", "/proc/123/auxv", "/proc/123/cmdline",
        "/proc/123/task/124", "/proc/123/task/124/exe", "/proc/123/task/124/mem",
        "/proc/123/task/124/auxv", "/proc/123/task/124/cmdline"};
    for (unsigned i=0;i<sizeof(public_dentries)/sizeof(*public_dentries);++i)
        assert(md_proc_public_task_dentry(md_proc_path_for(public_dentries[i],0,0)));
    const char *private_paths[]={"/proc", "/proc/123/fd", "/proc/123/fd/0",
        "/proc/123/exe/child", "/proc/123/root/etc/passwd", "/proc/123/mem/child",
        "/proc/123/auxv/child", "/proc/123/task/124/cmdline/child"};
    for (unsigned i=0;i<sizeof(private_paths)/sizeof(*private_paths);++i)
        assert(!md_proc_public_task_dentry(md_proc_path_for(private_paths[i],0,0)));
    char arguments[8192]; memset(arguments,'x',sizeof(arguments));
    arguments[sizeof(arguments)-1]=0;
    struct md_process_image view={.arguments=arguments,.argument_bytes=sizeof(arguments),
        .auxv_bytes=2*sizeof(Elf64_auxv_t)};
    Elf64_auxv_t aux[]={{.a_type=AT_PAGESZ,.a_un.a_val=4096},{.a_type=AT_NULL}};
    memcpy(view.auxv,aux,sizeof(aux));
    const unsigned kinds[]={MD_PROC_CMDLINE,MD_PROC_AUXV};
    for (unsigned i=0;i<sizeof(kinds)/sizeof(*kinds);++i) {
        unsigned kind=kinds[i];
        const void *expected=kind==MD_PROC_CMDLINE ? (void *)arguments : (void *)aux;
        size_t size=kind==MD_PROC_CMDLINE ? sizeof(arguments) : sizeof(aux);
        void *bytes=NULL;
        assert(md_process_image_snapshot(getpid(),(uintptr_t)&view,-1,kind,&bytes)==(ssize_t)size);
        assert(!memcmp(bytes,expected,size)); free(bytes);
    }
    void *bytes=NULL;
    assert(md_process_image_snapshot(getpid(),(uintptr_t)&view,-1,MD_PROC_EXE,&bytes)==-ENOTSUP && !bytes);
    view.arguments=(void *)1;
    assert(md_process_image_snapshot(getpid(),(uintptr_t)&view,-1,MD_PROC_CMDLINE,&bytes)==-EFAULT && !bytes);
    puts("PASS process snapshot: worker-independent cmdline/auxv and invalid memory");
}
static int accounting_enabled, accounting_requested, adoption_error, adoption_calls;
static pid_t wait_result=123;
static struct md_fs_buffer_open adopted={.fd=-1};
static unsigned lost_stop_reads;
static int lost_stop_code, lost_stop_error;
static long lost_stop_trace(int request,pid_t pid,void *address,void *data) {
    assert(request==PTRACE_GETSIGINFO && pid==123 && !address);
    ++lost_stop_reads;
    if (lost_stop_error) { errno=lost_stop_error; return -1; }
    *(siginfo_t *)data=(siginfo_t){.si_signo=SIGTRAP,.si_code=lost_stop_code};
    return 0;
}
static void lost_stop_delivery(void) {
    struct thread t={.pid=123};
    checkpoints.trace=lost_stop_trace;
    assert(!lost_stop_event(&t,PTRACE_EVENT_SECCOMP) && !lost_stop_reads);
    t.resume_lost=1;
    assert(!lost_stop_event(&t,PTRACE_EVENT_EXIT) && !lost_stop_reads);
    assert(!lost_stop_event(&t,PTRACE_EVENT_EXEC) && !lost_stop_reads);
    const unsigned events[]={PTRACE_EVENT_EXIT,PTRACE_EVENT_EXEC};
    for (unsigned i=0;i<2;++i) {
        lost_stop_code=SIGTRAP|(events[i]<<8);
        assert(lost_stop_event(&t,PTRACE_EVENT_SECCOMP));
        assert(t.resume_lost && t.pid==123 && !t.terminal_stop);
    }
    lost_stop_error=ESRCH;
    assert(lost_stop_event(&t,0) && t.resume_lost && lost_stop_reads==3);
    lost_stop_error=0; lost_stop_code=SIGTRAP|(PTRACE_EVENT_SECCOMP<<8);
    assert(!md_task_stop_displaced(lost_stop_trace,t.pid));
    lost_stop_error=EIO;
    assert(md_task_stop_displaced(lost_stop_trace,t.pid)==-EIO);
    lost_stop_error=0; checkpoints.trace=checkpoint_native_trace;
    puts("PASS lost stop: stale broker event discarded, terminal ownership retained, live/mismatched stops not hidden");
}
static struct user_pt_regs memory_saved;
static unsigned memory_reads;
static int memory_read_error;
static long memory_context_trace(int request,pid_t pid,void *address,void *data) {
    assert(request==PTRACE_GETREGSET && pid==123 && (uintptr_t)address==NT_PRSTATUS);
    ++memory_reads;
    if (memory_read_error) { errno=ESRCH; return -1; }
    struct iovec *io=data;
    assert(io->iov_len==sizeof(memory_saved));
    memcpy(io->iov_base,&memory_saved,sizeof(memory_saved));
    return 0;
}
static void memory_context_capture(void) {
    struct thread t={.pid=123,.memory_op.arguments_only=1};
    t.execution.request=(struct seccomp_data){.nr=SYS_mprotect,.instruction_pointer=0x4004,
        .args={0x10000,4096,PROT_READ}};
    const struct seccomp_data request=t.execution.request;
    thread_index[t.pid%THREAD_BUCKETS]=&t;
    checkpoints.trace=memory_context_trace;
    memory_saved=md_task_registers_entry(0x1004,0x8000,UINT64_C(1)<<12);
    for (unsigned i=0;i<31;++i) memory_saved.regs[i]=0x100+i;
    memory_saved.regs[8]=SYS_mmap;
    struct user_pt_regs operands={.pc=memory_saved.pc,.sp=memory_saved.sp};
    assert(memory_context(&t,&operands));
    assert(!t.memory_op.arguments_only && memory_reads==1);
    assert(!memcmp(&operands,&memory_saved,sizeof(operands)));
    assert(!memcmp(&t.execution.registers,&memory_saved,sizeof(t.execution.registers)));
    assert(!memcmp(&t.execution.request,&request,sizeof(request)));
    assert(memory_context(&t,&operands) && memory_reads==1);
    t.memory_op.arguments_only=1; memory_read_error=1;
    assert(!memory_context(&t,&operands) && t.resume_lost && t.memory_op.arguments_only);
    assert(memory_reads==2 && !memcmp(&operands,&memory_saved,sizeof(operands)));
    memory_read_error=0; checkpoints.trace=checkpoint_native_trace;
    thread_index[t.pid%THREAD_BUCKETS]=NULL;
    puts("PASS memory observation: lazy complete GPR capture, processor controls and lost-task cancellation");
}
static void memory_shared_mutations(void) {
    const struct seccomp_data calls[]={
        {.nr=SYS_mmap,.args={0,4096,PROT_READ,MAP_PRIVATE|MAP_ANONYMOUS,(uint64_t)-1,0}},
        {.nr=SYS_munmap,.args={0x10000,4096}},
        {.nr=SYS_mprotect,.args={0x10000,4096,PROT_READ}},
        {.nr=SYS_mremap,.args={0x10000,4096,8192,MREMAP_MAYMOVE}}};
    for (unsigned i=0;i<sizeof(calls)/sizeof(*calls);++i) {
        struct md_entry_channel channel={.call={.reserved=MD_ENTRY_EXECUTION_BEGIN}};
        struct thread t={.pid=123,.memory=md_memory_new(),.shm_space=md_shm_space_new(),
            .entry={.control=&channel,.admission=1}};
        assert(t.memory && t.shm_space);
        memset(&t.execution.registers,0xa5,sizeof(t.execution.registers));
        struct user_pt_regs untouched=t.execution.registers;
        memory_begin(&t,&calls[i],NULL,MEMORY_KERNEL);
        assert(t.phase==IDLE && t.entry.effect==memory_entry_complete);
        assert(t.memory_op.action==MEMORY_KERNEL && !entry_fence_drained(&t) && !debugger_at_boundary(&t));
        assert(atomic_load(&channel.state)==MD_ENTRY_NATIVE && channel.native.number==calls[i].nr);
        assert(!memcmp(channel.native.args,calls[i].args,sizeof(calls[i].args)));
        assert(!memcmp(&untouched,&t.execution.registers,sizeof(untouched)));
        struct md_resource_order next={0};
        assert(!md_memory_order_begin(t.memory,&next,NULL));
        memory_kernel_complete(&t,-ENOMEM);
        assert(!t.memory_op.action && !t.memory_op.order.queue && !t.memory_op.change.prepared);
        assert(md_resource_order_state(&next)==MD_RESOURCE_ORDER_READY);
        assert(!md_resource_order_cancel(&next));
        assert(!md_shm_space_release(t.shm_space)); md_memory_release(t.memory);
    }
    puts("PASS shared memory mutations: typed effects, unchanged GPRs, exact publication and ordered completion");
}
static void entry_revocation_lost_stop(void) {
    struct thread t={.pid=INT_MAX,.memory=md_memory_new()}; assert(t.memory);
    struct md_svc_registry *r=md_svc_registry_new(0x1000000,0x4000,0x5000); assert(r);
    assert(!md_memory_svc_set(t.memory,r));
    assert(!svc_revoke_entry(&t) && t.resume_lost && r->disabled && r->revoke_pending);
    t.resume_lost=0;
    struct seccomp_data call={.nr=SYS_seccomp,.args={[1]=SECCOMP_FILTER_FLAG_TSYNC}};
    assert(!filter_revoke_entry(&t,&call) && t.resume_lost && r->revoke_pending);
    assert(!t.seccomp.install && !t.entry.blocked);
    md_memory_release(t.memory);
    puts("PASS entry revocation: lost stop retains mm retry without committing a filter");
}
static void memory_observation_routes(void) {
    struct thread t={.pid=123,.memory=md_memory_new()}; assert(t.memory);
    struct md_svc_registry *r=md_svc_registry_new(0x1000000,0x4000,0x5000); assert(r);
    assert(!md_memory_svc_set(t.memory,r));
    assert(!md_svc_registry_pool_reserve(r));
    r->pool_count=1; r->pools[0]=(struct md_svc_code_pool){.address=0x3000,.used=8};
    r->count=1; r->sites[0]=(struct md_svc_site){.direct={.svc=0x2000,.gate=0x3000,.number=SYS_mmap},
        .kernel_alias=0x3000,.state=MD_SVC_PUBLISHED};
    struct ptrace_syscall_info info={.arch=AUDIT_ARCH_AARCH64,.instruction_pointer=0x2004,
        .stack_pointer=0x800000,.seccomp={.nr=SYS_mmap,.ret_data=MD_INTERCEPT_MEMORY,
            .args={0x1000,0x2000,PROT_READ,MAP_PRIVATE,19,0x3000}}};
    struct seccomp_data observed;
    int alias;
    assert(memory_observation(&t,&info,&observed,&alias) && !alias);
    assert(observed.instruction_pointer==info.instruction_pointer && observed.arch==info.arch
        && observed.nr==SYS_mmap && !memcmp(observed.args,info.seccomp.args,sizeof(info.seccomp.args)));
    info.instruction_pointer=0x3004;
    assert(memory_observation(&t,&info,&observed,&alias) && alias==1);
    assert(observed.instruction_pointer==0x3004 && observed.args[4]==19);
    info.seccomp.nr=SYS_munmap;
    assert(memory_observation(&t,&info,&observed,&alias) && alias==2);
    r->sites[0].state=MD_SVC_RETIRED;
    assert(!memory_observation(&t,&info,&observed,&alias));
    r->sites[0].state=MD_SVC_PUBLISHED;
    info.stack_pointer=r->frames+16;
    assert(!memory_observation(&t,&info,&observed,&alias));
    info.stack_pointer=0x800000; info.instruction_pointer=0x3000;
    assert(!memory_observation(&t,&info,&observed,&alias));
    info.instruction_pointer=0x3004; r->disabled=1;
    assert(!memory_observation(&t,&info,&observed,&alias)); r->disabled=0;
    t.seccomp.application_filter=1;
    assert(!memory_observation(&t,&info,&observed,&alias)); t.seccomp.application_filter=0;
    t.entry.blocked=1;
    assert(!memory_observation(&t,&info,&observed,&alias)); t.entry.blocked=0;
    t.debug.options=1;
    assert(!memory_observation(&t,&info,&observed,&alias)); t.debug.options=0;
    t.entry.fence_result=-EPERM;
    assert(!memory_observation(&t,&info,&observed,&alias)); t.entry.fence_result=0;
    info.seccomp.ret_data=MD_INTERCEPT_PROC;
    assert(!memory_observation(&t,&info,&observed,&alias));
    md_memory_release(t.memory);
    puts("PASS compact memory routes: original/known/cold alias, exact operands, no private stack, retired gate, filter or debugger");
}
static void machine_context_admission(void) {
    struct thread t={.pid=123,.entered=1,.memory=md_memory_new()}; assert(t.memory);
    struct ptrace_syscall_info info={.op=PTRACE_SYSCALL_INFO_SECCOMP,
        .instruction_pointer=0x2004,.stack_pointer=0x800000,.seccomp={.nr=SYS_close}};
    assert(!machine_context(&t,&info) && !machine_discovery(&t));
    struct md_svc_registry *r=md_svc_registry_new(0x1000000,0x4000,0x5000); assert(r);
    assert(!md_memory_svc_set(t.memory,r));
    assert(!md_svc_registry_pool_reserve(r));
    r->pool_count=1; r->pools[0]=(struct md_svc_code_pool){.address=0x3000,.used=8};
    assert(machine_context(&t,&info) && machine_discovery(&t));
    t.seccomp.notification_continued=1;
    assert(!machine_discovery(&t));
    t.seccomp.notification_continued=0; t.seccomp.admission_policy_valid=1;
    assert(!machine_discovery(&t));
    t.seccomp.admission_policy_valid=0;
    for (unsigned i=0;i<4;++i) {
        t.seccomp.application_filter=i==0; t.entry.blocked=i==1;
        t.debug.options=i==2; r->disabled=i==3;
        assert(!machine_discovery(&t) && !machine_context(&t,&info));
        info.instruction_pointer=0x3004;
        assert(machine_context(&t,&info));
        info.instruction_pointer=0x2004; info.stack_pointer=r->frames+16;
        assert(machine_context(&t,&info));
        info.stack_pointer=0x800000;
        assert(machine_context(&t,NULL));
    }
    t.seccomp.application_filter=t.entry.blocked=0; t.debug.options=0; r->disabled=0;
    t.entered=0;
    assert(!machine_discovery(&t) && !machine_context(&t,&info));
    t.entered=1;
    for (unsigned nr=0;nr<512;++nr) {
        info.seccomp.nr=nr;
        assert(machine_context(&t,&info));
    }
    info.seccomp.nr=512;
    assert(!machine_context(&t,&info));
    entry_fence_owner=&t;
    assert(!machine_discovery(&t));
    entry_fence_owner=NULL;
    info.instruction_pointer=0x3004;
    assert(machine_context(&t,&info));
    t.phase=DISPATCHING;
    assert(!machine_context(&t,&info) && !machine_context(&t,NULL));
    t.phase=IDLE; t.seccomp.application_filter=1;
    struct sock_filter code[]={BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
    struct sock_fprog program={1,code};
    assert(!md_seccomp_policy_capture(NULL,&program,&t.seccomp.policy));
    assert(!machine_discovery(&t));
    assert(!md_seccomp_policy_bind_entry(t.seccomp.policy,r->image,NULL,0));
    assert(!machine_discovery(&t));
    struct md_interception_buffers *pool=NULL;
    assert(!md_buffers_create_readonly(1,128,&pool));
    assert(!md_memory_snapshot_publish(t.memory,md_seccomp_policy_identity(t.seccomp.policy),pool,0x200000));
    assert(machine_discovery(&t));
    const long mapping_calls[]={SYS_mmap,SYS_munmap,SYS_mprotect,SYS_mremap};
    for (unsigned i=0;i<sizeof(mapping_calls)/sizeof(*mapping_calls);++i) {
        __u64 args[]={0x200000,4096,4096,MAP_FIXED,0x400000,0};
        assert(entry_snapshot_change(&t,mapping_calls[i],args));
        args[0]=0x400000;
        assert(!entry_snapshot_change(&t,mapping_calls[i],args));
    }
    __u64 args[]={0x400000,4096,4096,MREMAP_MAYMOVE|MREMAP_FIXED,0x200000,0};
    assert(entry_snapshot_change(&t,SYS_mremap,args));
    args[2]=SHM_REMAP;
    assert(entry_snapshot_change(&t,SYS_shmat,args));
    assert(!entry_snapshot_change(&t,SYS_read,args));
    struct thread child=t;
    child.memory=md_memory_fork(t.memory,0); assert(child.memory);
    assert(machine_discovery(&child));
    struct md_interception_memory *image=md_memory_new_image(child.memory); assert(image);
    md_memory_release(child.memory); child.memory=image;
    assert(!machine_discovery(&child)); md_memory_release(image);
    md_memory_snapshots_invalidate(t.memory);
    assert(!machine_discovery(&t));
    md_buffers_release(pool); md_seccomp_policy_release(t.seccomp.policy);
    md_memory_release(t.memory);
    puts("PASS machine context: lazy discovery capture, revoked entry recovery and task-owned dispatch");
    puts("PASS policy entry admission: published view, exact image, fork/exec and mapping mutation scope");
}
static void native_alias_routing(void) {
    struct thread t={.pid=123,.entered=1,.seccomp.application_filter=1,.memory=md_memory_new()};
    assert(t.memory);
    struct md_svc_registry *r=md_svc_registry_new(0x1000000,0x4000,0x5000); assert(r);
    assert(!md_memory_svc_set(t.memory,r));
    assert(!md_svc_registry_pool_reserve(r));
    r->pool_count=1; r->pools[0]=(struct md_svc_code_pool){.address=0x3000,.used=8};
    r->count=1;
    r->sites[0]=(struct md_svc_site){.direct={.svc=0x2000,.gate=0x3000,.number=SYS_getpid},.kernel_alias=0x3000};
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,0x2004,0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,17,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES)};
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!md_seccomp_policy_capture(NULL,&program,&t.seccomp.policy));
    assert(!md_seccomp_policy_bind_entry(t.seccomp.policy,r->image,NULL,0));
    struct md_interception_buffers *pool=NULL;
    assert(!md_buffers_create_readonly(1,128,&pool));
    assert(!md_memory_snapshot_publish(t.memory,md_seccomp_policy_identity(t.seccomp.policy),pool,0x200000));
    struct seccomp_data call={.instruction_pointer=0x3004,.arch=AUDIT_ARCH_AARCH64};
    const long calls[]={SYS_getpid,SYS_munmap,SYS_close};
    const unsigned expected[]={MD_INTERCEPT_NATIVE,MD_INTERCEPT_MEMORY,MD_INTERCEPT_CLOSE|7};
    for (unsigned i=0;i<sizeof(calls)/sizeof(*calls);++i) {
        call.nr=calls[i]; call.args[0]=7;
        unsigned long cookie=MD_INTERCEPT_TASK;
        assert(machine_native_entry(&t,&call,&cookie)==(i ? 2 : 1) && cookie==expected[i]);
        struct ptrace_syscall_info info={.op=PTRACE_SYSCALL_INFO_SECCOMP,.arch=call.arch,
            .instruction_pointer=call.instruction_pointer,.stack_pointer=0x800000,
            .seccomp={.nr=call.nr}};
        memcpy(info.seccomp.args,call.args,sizeof(info.seccomp.args));
        cookie=MD_INTERCEPT_TASK;
        assert(machine_observed_entry(&t,&info,&cookie)==(i ? 2 : 1) && cookie==expected[i]);
        info.stack_pointer=r->frames+16; cookie=MD_INTERCEPT_TASK;
        assert(!machine_observed_entry(&t,&info,&cookie) && cookie==MD_INTERCEPT_TASK);
        info.stack_pointer=0x800000; info.seccomp.args[0]=17;
        assert(!machine_observed_entry(&t,&info,&cookie) && cookie==MD_INTERCEPT_TASK);
        call.args[0]=17; cookie=MD_INTERCEPT_TASK;
        assert(!machine_native_entry(&t,&call,&cookie) && cookie==MD_INTERCEPT_TASK);
    }
    call.nr=SYS_getpid; call.args[0]=7; t.process_op.filter=1;
    unsigned long cookie=MD_INTERCEPT_TASK;
    assert(machine_native_entry(&t,&call,&cookie)==1 && cookie==MD_INTERCEPT_PROCESS);
    call.nr=SYS_openat; cookie=MD_INTERCEPT_TASK;
    assert(!machine_native_entry(&t,&call,&cookie) && cookie==MD_INTERCEPT_TASK);
    call.nr=SYS_prctl; call.args[0]=PR_SET_SECCOMP; call.args[1]=SECCOMP_MODE_FILTER;
    assert(!machine_native_entry(&t,&call,&cookie));
    md_buffers_release(pool); md_seccomp_policy_release(t.seccomp.policy); md_memory_release(t.memory);
    puts("PASS native alias: original IP/arguments, shared native/PID/memory/FD routes and notification/filter fallback");
}
static enum md_execution_context dispatch_context(long nr,const uint64_t args[6],int control) {
    struct md_guest_execution call={.number=nr,.control=control};
    memcpy(call.args,args,sizeof(call.args));
    return md_execution_dispatch_context(&call);
}
static void execution_context_contract(void) {
    uint64_t args[6]={0};
    assert(md_execution_native_context(SYS_uname,args)==MD_CONTEXT_COUNT);
    assert(md_execution_native_context(SYS_clone,args)==MD_CONTEXT_BIRTH);
    assert(md_execution_native_context(SYS_rt_sigreturn,args)==MD_CONTEXT_SIGNAL);
    assert(md_execution_native_context(SYS_rt_sigprocmask,args)==MD_CONTEXT_COUNT);
    args[1]=1;
    assert(md_execution_native_context(SYS_rt_sigprocmask,args)==MD_CONTEXT_SIGNAL);
    args[1]=0;
    assert(md_execution_native_context(SYS_set_robust_list,args)==MD_CONTEXT_PROCESS);
    assert(md_execution_native_context(SYS_set_tid_address,args)==MD_CONTEXT_COUNT);
    assert(md_execution_native_context(SYS_ptrace,args)==MD_CONTEXT_DEBUG);
    const long waits[]={SYS_read,SYS_write,SYS_futex,SYS_wait4,SYS_connect,SYS_recvmsg};
    for (unsigned i=0;i<sizeof(waits)/sizeof(*waits);++i)
        assert(md_execution_native_context(waits[i],args)==MD_CONTEXT_WAIT);
    const long sockets[]={SYS_sendto,SYS_recvfrom,SYS_sendmsg,SYS_recvmsg,SYS_sendmmsg,SYS_recvmmsg};
    for (unsigned i=0;i<sizeof(sockets)/sizeof(*sockets);++i) {
        unsigned flag=sockets[i]==SYS_sendmsg || sockets[i]==SYS_recvmsg ? 2 : 3;
        args[flag]=MSG_DONTWAIT;
        assert(md_execution_native_context(sockets[i],args)==MD_CONTEXT_COUNT);
        args[flag]=0;
        assert(md_execution_native_context(sockets[i],args)==MD_CONTEXT_WAIT);
    }
    for (unsigned receive=0;receive<2;++receive) {
        long nr=receive ? SYS_msgrcv : SYS_msgsnd;
        args[3+receive]=IPC_NOWAIT;
        assert(md_execution_native_context(nr,args)==MD_CONTEXT_COUNT);
        args[3+receive]=0;
        assert(md_execution_native_context(nr,args)==MD_CONTEXT_WAIT);
    }
    assert(md_execution_native_context(SYS_epoll_pwait,args)==MD_CONTEXT_COUNT);
    args[3]=-1;
    assert(md_execution_native_context(SYS_epoll_pwait,args)==MD_CONTEXT_WAIT);
    args[3]=0; args[4]=1;
    assert(md_execution_native_context(SYS_epoll_pwait,args)==MD_CONTEXT_WAIT);
    args[4]=0;
    const long shared_waits[]={SYS_epoll_pwait,SYS_epoll_pwait2,SYS_ppoll,SYS_pselect6};
    for (unsigned i=0;i<sizeof(shared_waits)/sizeof(*shared_waits);++i) {
        unsigned mask=shared_waits[i]==SYS_ppoll ? 3 : shared_waits[i]==SYS_pselect6 ? 5 : 4;
        assert(dispatch_context(shared_waits[i],args,-1)==MD_CONTEXT_COUNT);
        args[mask]=1;
        assert(dispatch_context(shared_waits[i],args,-1)==MD_CONTEXT_SIGNAL);
        args[mask]=0;
    }
    /* The C owner has selected the adapter. Adding another syscall must not
     * require adding it to a transport allow-list as well. */
    for (long nr=0;nr<512;++nr) {
        enum md_execution_context expected=nr==SYS_execve || nr==SYS_execveat ? MD_CONTEXT_EXEC
            : nr==SYS_rt_sigsuspend ? MD_CONTEXT_SIGNAL : MD_CONTEXT_COUNT;
        assert(dispatch_context(nr,args,-1)==expected);
    }
    const unsigned queries[]={FIONREAD,TIOCGWINSZ,TCGETS,TCGETS2};
    for (unsigned i=0;i<sizeof(queries)/sizeof(*queries);++i) {
        args[1]=queries[i];
        assert(md_execution_native_context(SYS_ioctl,args)==MD_CONTEXT_COUNT);
        assert(dispatch_context(SYS_ioctl,args,-1)==MD_CONTEXT_COUNT);
    }
    args[1]=TCSETSW2;
    assert(md_execution_native_context(SYS_ioctl,args)==MD_CONTEXT_WAIT);
    assert(dispatch_context(SYS_ioctl,args,-1)==MD_CONTEXT_COUNT);
    args[1]=0xdeadbeef;
    assert(md_execution_native_context(SYS_ioctl,args)==MD_CONTEXT_WAIT);
    assert(dispatch_context(SYS_ioctl,args,-1)==MD_CONTEXT_COUNT);
    assert(dispatch_context(SYS_ioctl,args,MD_PROC_FILE_CONTROL)==MD_CONTEXT_COUNT);
    const unsigned control_commands[]={MD_BUFFER_CONTROL_IOCTL,MD_BUFFER_CONTROL_CAPTURE,
        MD_BUFFER_CONTROL_CAPTURE_READY,MD_BUFFER_CONTROL_CAPTURE_SENT,MD_BUFFER_CONTROL_CAPTURE_OBSERVED};
    for (unsigned i=0;i<sizeof(control_commands)/sizeof(*control_commands);++i)
        assert(md_adapter_buffer_shared(control_commands[i]));
    puts("PASS execution context: all C calls share admission; native waits, masks and original-stack owners stay distinct");
}
static void processor_controls(void) {
    const uint64_t controls[]={0,UINT64_C(1)<<12,UINT64_C(1)<<24,(UINT64_C(1)<<12)|(UINT64_C(1)<<24)};
    for (unsigned i=0;i<sizeof(controls)/sizeof(*controls);++i) {
        struct user_pt_regs regs=md_task_registers_entry(0x1000,0x2000,controls[i]|UINT64_C(0xf0000000));
        assert(regs.pc==0x1000 && regs.sp==0x2000 && regs.pstate==controls[i]);
        for (unsigned j=0;j<sizeof(regs.regs)/sizeof(*regs.regs);++j) assert(!regs.regs[j]);
    }
    puts("PASS fresh continuation: operands/NZCV cleared, processor controls retained");
    for (unsigned offset=0;offset<=12;offset+=4) {
        struct user_pt_regs r={.pc=0x1004+offset};
        r.regs[8]=offset<8 ? SYS_mmap : SYS_gettid;
        r.regs[19]=offset ? SYS_mmap : 0;
        assert(md_task_registers_call_result(&r,0x1004,0x100c,SYS_mmap));
        assert(!md_task_registers_call_result(&r,0x1004,0x100c,SYS_munmap));
        r.pc++;
        assert(!md_task_registers_call_result(&r,0x1004,0x100c,SYS_mmap));
    }
    struct user_pt_regs outside={.pc=0x1014};
    outside.regs[8]=SYS_gettid; outside.regs[19]=SYS_mmap;
    assert(!md_task_registers_call_result(&outside,0x1004,0x100c,SYS_mmap));
    puts("PASS service call result: exact return/marker positions, saved number, reject unrelated state");
}
static void svc_client_lifetime(void) {
    struct md_interception_memory *memory=md_memory_new(); assert(memory);
    struct md_interception_memory *other=md_memory_new(); assert(other);
    struct thread t={.pid=111,.memory=memory};
    unsigned bucket=(unsigned)t.pid%THREAD_BUCKETS;
    assert(!thread_index[bucket]); thread_index[bucket]=&t;
    assert(svc_client_alive(t.pid,memory) && !svc_client_alive(t.pid,other));
    t.resume_lost=1;
    assert(svc_client_alive(t.pid,memory));
    t.terminal_stop=1;
    assert(!svc_client_alive(t.pid,memory));
    thread_index[bucket]=NULL;
    assert(!svc_client_alive(t.pid,memory));
    md_memory_release(other); md_memory_release(memory);
    puts("PASS SVC client lifetime: exact mm, lost stop retains owner, terminal event and reap retire it");
}
static void receive_error_queue(void) {
    const int families[]={AF_INET,AF_INET6};
    for (unsigned i=0;i<2;++i) {
        int fd=socket(families[i],SOCK_DGRAM|SOCK_CLOEXEC,0); assert(fd>=0);
        struct md_received_fds *fds=NULL;
        assert(!md_received_fds_reserve(&fds,1));
        fds->fds[fds->count++]=fd;
        struct md_ipc_io op={0};
        struct md_ipc_io_message *io=calloc(1,sizeof(*io)); assert(io);
        io->size=16; io->control_size=128; io->address_size=sizeof(io->address);
        long result=0;
        assert(!md_ipc_io_receive(&op,&fds,io,MSG_ERRQUEUE,&result));
        assert(result==-EAGAIN && op.nonblocking && !op.job.started);
        md_ipc_io_release(&op); md_received_fds_release(fds); free(io);
    }
    puts("PASS empty IP error queue: immediate EAGAIN on blocking IPv4/IPv6 sockets");
}
static void receive_layouts(void) {
    struct md_ipc_io_message *io=calloc(1,sizeof(*io)); assert(io);
    struct md_ipc_io op={0};
    int sockets[2]; assert(!socketpair(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0,sockets));
    io->batch_count=MD_IPC_IO_BATCH;
    assert(md_ipc_io_prepare_batch(&op,io,sockets[0],MSG_DONTWAIT,0)==-EINVAL);
    assert(md_ipc_io_prepare_batch(&op,io,sockets[0],MSG_DONTWAIT,MD_IPC_IO_BATCH+1)==-EINVAL);
    for (unsigned i=0;i<MD_IPC_IO_BATCH;++i)
        io->layouts[i]=(struct md_ipc_io_layout){.size=i+1,.control_size=32};
    assert(!md_ipc_io_prepare_batch(&op,io,sockets[0],MSG_DONTWAIT,MD_IPC_IO_BATCH));
    assert(op.capacity==MD_IPC_IO_BATCH && op.control_capacity==32 && op.nonblocking);
    assert(md_ipc_io_prepare_batch(&op,io,sockets[0],MSG_DONTWAIT,1)==-EINVAL);
    md_ipc_io_release(&op);
    io->layouts[0].vector_error=-EFAULT;
    assert(md_ipc_io_prepare_batch(&op,io,sockets[0],MSG_DONTWAIT,2)==-EINVAL && !op.initialized);
    io->layouts[0].vector_error=0; io->layouts[1].vector_error=-EFAULT;
    assert(!md_ipc_io_prepare_batch(&op,io,sockets[0],MSG_DONTWAIT,2));
    md_ipc_io_release(&op);
    io->layouts[0].size=SIZE_MAX;
    assert(md_ipc_io_prepare_batch(&op,io,sockets[0],MSG_DONTWAIT,1)==-EINVAL && !op.initialized);
    close(sockets[0]); close(sockets[1]); free(io);
    puts("PASS receive batch layouts: bounded shared vector, one admission, late fault and invalid metadata");
}
static void native_capture(void) {
    int file=memfd_create("native-capture",MFD_CLOEXEC);
    assert(file>=0 && write(file,"ab",2)==2 && !lseek(file,0,SEEK_SET));
    int numbers[]={file,file};
    struct md_received_fds *received=NULL;
    assert(md_fd_capture_native(getpid(),numbers,2,&received)==1 && received->count==2);
    char byte;
    assert(read(received->fds[0],&byte,1)==1 && byte=='a');
    assert(read(received->fds[1],&byte,1)==1 && byte=='b');
    assert(lseek(file,0,SEEK_CUR)==2);
    int next=received->fds[0];
    md_received_fds_release(received); received=NULL;
    numbers[1]=-1;
    for (unsigned i=0;i<64;++i)
        assert(md_fd_capture_native(getpid(),numbers,2,&received)==-EBADF && !received);
    int copy=fcntl(file,F_DUPFD_CLOEXEC,0);
    assert(copy==next); close(copy);
    pid_t parent=getpid(),child=fork(); assert(child>=0);
    if (!child) {
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_pidfd_getfd,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        };
        struct sock_fprog policy={sizeof(code)/sizeof(*code),code};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&policy));
        assert(!md_fd_capture_native(parent,numbers,1,&received) && !received);
        _exit(0);
    }
    int status;
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    close(file);
    puts("PASS native capture: exact OFD, duplicate slots, partial rollback and denied-copy fallback");
}
int __wrap_md_debugger_active(struct md_debugger *d) {
    (void)d; return accounting_enabled;
}
pid_t __wrap_md_task_wait_ready(struct md_task_wait *w,int *status,struct rusage *usage) {
    (void)w;
    accounting_requested=usage!=NULL;
    if (wait_result>0) {
        *status=(SIGTRAP|0x80)<<8|0x7f;
        if (usage) *usage=(struct rusage){.ru_utime.tv_usec=123,.ru_maxrss=456};
    }
    return wait_result;
}
int __wrap_md_fs_buffer_adopt(struct md_fs_buffer_service *s,pid_t actor,struct md_guest_domain *fs,
        unsigned operation,unsigned flags,const struct md_interception_buffer *buffer,
        struct md_fs_result *result,struct md_fs_buffer_open *opened,struct md_fs_buffer_request **out) {
    (void)s; (void)fs; (void)operation; (void)flags;
    assert(actor>0 && buffer->pool && !result->error && opened->fd>=0 && !*out);
    ++adoption_calls;
    if (adoption_error) return adoption_error;
    adopted=*opened; *opened=(struct md_fs_buffer_open){.fd=-1};
    *out=(struct md_fs_buffer_request *)&adopted;
    return 0;
}
static void barrier_admission(void) {
    struct thread installer={0};
    struct mapping_owner mapping={0},other={0};
    struct task_action action={0},loan={0};
    struct memory_execution memory={0};
    struct retained_memory robust={0};
    struct retained_retirement retirement={0};
    assert(!quiescence_busy(NULL));
    retained_creation=&robust;
    assert(quiescence_busy(NULL) && quiescence_busy(&mapping));
    retained_creation=NULL; retained_retirement=&retirement;
    assert(quiescence_busy(NULL) && quiescence_busy(&mapping));
    retained_retirement=NULL;
    policy_installer=&installer;
    assert(quiescence_busy(NULL) && quiescence_busy(&mapping));
    /* Even a retirement without a requester must not acquire a table or actor. */
    loan_start(&loan);
    assert(!loan.files.table && !loan.actor && !task_action_execution && !cancelling);
    policy_installer=NULL;
    assert(!quiescence_busy(NULL));
    memory_execution=&memory;
    assert(quiescence_busy(NULL) && quiescence_busy(&mapping));
    memory_execution=NULL;
    task_action_execution=&action;
    assert(quiescence_busy(NULL) && quiescence_busy(&mapping));
    task_action_execution=NULL; task_action_releasing=1;
    assert(quiescence_busy(NULL) && quiescence_busy(&mapping));
    task_action_releasing=0; mapping_execution=&mapping;
    assert(quiescence_busy(NULL) && quiescence_busy(&other) && !quiescence_busy(&mapping));
    policy_installer=&installer;
    assert(quiescence_busy(&mapping));
    policy_installer=NULL; mapping_execution=NULL;
    puts("PASS supervisor admission: robust actors, policy, repair, injection, release and exact nested retirement");
}
static struct thread *action_cancel,*action_enqueue;
static unsigned actions_completed;
static void queued_action_complete(struct thread *t,long result);
static void ready_action(struct thread *t) {
    struct task_action *owner=task_action_new(t);
    assert(owner);
    owner->number=-1; owner->kind=MD_FD_SELECTOR_WATCH;
    owner->selected_group=md_fd_selector_new();
    assert(owner->selected_group);
    assert(!md_fd_selector_mark(owner->selected_group,owner->kind,-1));
    owner->complete=queued_action_complete;
}
static void queued_action_complete(struct thread *t,long result) {
    assert(!result && !t->action); ++actions_completed;
    if (action_cancel) { task_action_discard(action_cancel); action_cancel=NULL; }
    if (action_enqueue) { ready_action(action_enqueue); action_enqueue=NULL; }
}
static void queued_actions(void) {
    struct thread a={.pid=123},b={.pid=456},c={.pid=789};
    assert(!threads && !task_action_calls && !task_action_execution);
    ready_action(&a); ready_action(&b);
    policy_installer=&c;
    task_action_schedule();
    assert(a.retries[0].mask==RETRY_TASK_ACTION && b.retries[0].mask==RETRY_TASK_ACTION);
    assert(task_action_calls==2 && a.action->pending.list && b.action->pending.list);
    retry_clear(&a,RETRY_TASK_ACTION); retry_clear(&b,RETRY_TASK_ACTION);
    policy_installer=NULL;
    task_action_discard(&a);
    ready_action(&c);
    action_cancel=&c; action_enqueue=&a;
    task_action_schedule();
    assert(actions_completed==1 && !b.action && !c.action && a.action && task_action_calls==1);
    task_action_schedule();
    assert(actions_completed==2 && !a.action && !task_action_calls);
    /* An actor can outlive its requester; teardown cancels its queue node. */
    struct task_action *active=task_action_new(&a);
    assert(active); task_action_execution=active; active->filter.task.stage=1;
    task_action_discard(&a);
    task_action_schedule();
    assert(!a.action && !active->requester && task_action_calls==1);
    task_action_cleanup();
    assert(!task_action_calls && !task_action_execution);
    assert(!task_action_queue.lists[0].first && !task_action_queue.lists[1].first);
    puts("PASS action queue: no population scan, blocked retry, cancellation, deferred insertion and detached teardown");
}
static long readiness_return_trace(int request,pid_t pid,void *address,void *data) {
    assert(request==PTRACE_SETREGSET && pid==123 && (uintptr_t)address==NT_PRSTATUS);
    struct user_pt_regs *regs=((struct iovec *)data)->iov_base;
    assert((long)regs->regs[0]==MD_EPOLL_NATIVE);
    errno=ESRCH; return -1;
}
static void readiness_admission_order(void) {
    struct md_fd_origins *files=md_fd_origins_new();
    struct md_fd_selector *group=md_fd_selector_new();
    struct md_control_epoll *queue=md_control_epoll_new();
    assert(files && group && queue);
    struct readiness_owner prior={.loans={{.number=-1},{.number=-1}}},activation={0};
    struct thread t={.pid=123,.phase=DISPATCHING,.readiness=&prior};
    t.resources.files=files; t.execution.request.args[0]=5; t.execution.request.nr=SYS_epoll_pwait;
    assert(md_fd_order_begin(files,&prior.files,NULL)==1);
    activation.admission=md_fd_admission_new(md_control_epoll_object(queue),1U<<MD_FD_SELECTOR_EPOLL);
    assert(activation.admission && !md_fd_admission_add(activation.admission,files,group));
    assert(!md_fd_admission_begin(activation.admission,NULL));
    assert(!md_fd_admission_prepare(activation.admission));
    readiness_activation=&activation;
    thread_index[t.pid%THREAD_BUCKETS]=&t;
    checkpoints.trace=readiness_return_trace;
    readiness_start(&t);
    checkpoints.trace=checkpoint_native_trace;
    thread_index[t.pid%THREAD_BUCKETS]=NULL;
    assert(prior.done && !prior.files.table && t.resume_lost);
    assert(md_fd_admission_prepare(activation.admission)==1);
    assert(!t.retries[0].mask && !t.retries[1].mask);
    readiness_activation=NULL;
    md_fd_admission_release(activation.admission);
    md_control_epoll_release(queue); md_fd_selector_release(group); md_fd_origins_release(files);
    puts("PASS supervisor readiness: earlier table turn drains during cold admission");
}
static unsigned readiness_cancel_step;
static struct user_pt_regs readiness_before_call;
static unsigned readiness_entry_reads,readiness_entry_resumes;
static long readiness_entry_trace(int request,pid_t pid,void *address,void *data) {
    assert(pid==123);
    if (request==PTRACE_GETREGSET) {
        assert((uintptr_t)address==NT_PRSTATUS);
        *(struct user_pt_regs *)((struct iovec *)data)->iov_base=readiness_before_call;
        ++readiness_entry_reads;
    } else {
        assert(request==PTRACE_SYSCALL && !address && !data);
        ++readiness_entry_resumes;
    }
    return 0;
}
static void readiness_entry_interrupt(void) {
    uintptr_t call=abi.task_call,returned=abi.task_return;
    abi.task_call=0x1000; abi.task_return=0x1004;
    const long numbers[]={SYS_epoll_pwait,SYS_epoll_pwait2,SYS_ppoll};
    for (unsigned i=0;i<sizeof(numbers)/sizeof(*numbers);++i) for (unsigned evict=0;evict<2;++evict) {
        struct readiness_owner owner={.evicting=evict,
            .native_call={.number=numbers[i],.value={35,0x4000,2,1000,0,8}}};
        struct thread t={.pid=123,.phase=EPOLL_CALL,.kernel_stopped=1,
            .io.socket_waking=1,.signals.shielded=1,.readiness=&owner};
        readiness_before_call=(struct user_pt_regs){.pc=abi.task_call};
        readiness_before_call.regs[8]=numbers[i];
        memcpy(readiness_before_call.regs,owner.native_call.value,sizeof(owner.native_call.value));
        readiness_entry_reads=readiness_entry_resumes=0;
        thread_index[t.pid%THREAD_BUCKETS]=&t; checkpoints.trace=readiness_entry_trace;
        int status=(PTRACE_EVENT_STOP<<16)|(SIGTRAP<<8)|0x7f;
        assert(readiness_native_event(&t,status));
        assert(readiness_entry_reads==1 && readiness_entry_resumes==1);
        assert(t.phase==EPOLL_CALL && !t.io.socket_waking && !t.kernel_stopped && t.signals.shielded);
        assert(!owner.done && !owner.finishing && !owner.parked && owner.evicting==(int)evict);
        assert(!readiness_native_event(&t,(SIGUSR1<<8)|0x7f));
        assert(readiness_entry_reads==1 && readiness_entry_resumes==1);
        checkpoints.trace=checkpoint_native_trace; thread_index[t.pid%THREAD_BUCKETS]=NULL;
    }
    abi.task_call=call; abi.task_return=returned;
    puts("PASS readiness entry interrupt: resume exact call, retain eviction and mask, consume only service wake");
}
static unsigned readiness_ignored_reads,readiness_ignored_writes,readiness_ignored_resumes;
static long readiness_ignored_trace(int request,pid_t pid,void *address,void *data) {
    assert(pid==123);
    if (request==PTRACE_GETREGSET || request==PTRACE_SETREGSET) {
        assert((uintptr_t)address==NT_PRSTATUS);
        struct user_pt_regs *regs=((struct iovec *)data)->iov_base;
        if (request==PTRACE_GETREGSET) {
            *regs=(struct user_pt_regs){.pc=abi.native_call,.regs={-514}};
            ++readiness_ignored_reads;
        } else {
            assert(regs->pc==abi.native_end && (long)regs->regs[0]==-EINTR);
            ++readiness_ignored_writes;
        }
    } else {
        assert(request==PTRACE_CONT && !address && !data);
        ++readiness_ignored_resumes;
    }
    return 0;
}
static void readiness_ignored_effect(void) {
    uintptr_t begin=abi.native_begin,call=abi.native_call,end=abi.native_end;
    abi.native_begin=0x1000; abi.native_call=0x1040; abi.native_end=0x1080;
    const unsigned states[]={MD_NATIVE_READY,MD_NATIVE_RUNNING,MD_NATIVE_DONE};
    const long numbers[]={SYS_ppoll,SYS_epoll_pwait,SYS_epoll_pwait2};
    for (unsigned i=0;i<3;++i) for (unsigned n=0;n<3;++n) {
        struct md_entry_channel channel={.native={.state=states[i],.number=numbers[n],.result=1}};
        struct readiness_owner owner={0};
        struct thread t={.pid=123,.phase=DISPATCHING,.kernel_stopped=1,.readiness=&owner,
            .entry={.control=&channel,.execution=&channel,.effect=readiness_effect_done,.waiting=1},
            .signals.actions=md_signal_actions_new()};
        struct md_kernel_action action={.handler=(uintptr_t)SIG_DFL};
        assert(t.signals.actions && !md_signal_actions_commit(t.signals.actions,SIGCHLD,&action));
        struct md_task_signal_stop stop={.has_info=1,.info={.si_signo=SIGCHLD,.si_code=CLD_EXITED}};
        thread_index[t.pid%THREAD_BUCKETS]=&t; checkpoints.trace=readiness_ignored_trace;
        readiness_ignored_reads=readiness_ignored_writes=readiness_ignored_resumes=0;
        assert(readiness_signal_event(&t,SIGCHLD,&stop,0));
        assert(t.io.socket_waking && !t.kernel_stopped && t.entry.effect==readiness_effect_done);
        assert(atomic_load(&channel.native.state)==(states[i]==MD_NATIVE_DONE ? MD_NATIVE_DONE : MD_NATIVE_CANCELLED));
        assert(channel.native.result==(states[i]==MD_NATIVE_DONE ? 1 : -EINTR));
        assert(readiness_ignored_reads==(states[i]==MD_NATIVE_RUNNING));
        assert(readiness_ignored_writes==readiness_ignored_reads && readiness_ignored_resumes==1);
        checkpoints.trace=checkpoint_native_trace; thread_index[t.pid%THREAD_BUCKETS]=NULL;
        md_signal_actions_release(t.signals.actions);
    }
    abi.native_begin=begin; abi.native_call=call; abi.native_end=end;
    puts("PASS ignored readiness signal: resident effect reaches its retained completion without stranded restart");
}
static unsigned readiness_terminal_queries;
static long readiness_terminal_trace(int request,pid_t pid,void *address,void *data) {
    assert(pid==123);
    if (request==PTRACE_GETREGSET) {
        assert((uintptr_t)address==NT_PRSTATUS);
        *(struct user_pt_regs *)((struct iovec *)data)->iov_base=readiness_before_call;
    } else if (request==PTRACE_GET_SYSCALL_INFO) {
        *(struct ptrace_syscall_info *)data=(struct ptrace_syscall_info){.op=PTRACE_SYSCALL_INFO_NONE};
    } else {
        assert(request==PTRACE_GETSIGINFO && !address);
        ++readiness_terminal_queries;
        *(siginfo_t *)data=(siginfo_t){.si_signo=SIGTRAP,.si_code=SIGTRAP|(PTRACE_EVENT_EXIT<<8)};
    }
    return 0;
}
static void readiness_terminal_replacement(void) {
    struct readiness_owner owner={.native_call={.number=SYS_epoll_pwait}};
    struct thread t={.pid=123,.phase=EPOLL_CALL,.readiness=&owner};
    readiness_before_call=(struct user_pt_regs){.pc=abi.task_return};
    readiness_before_call.regs[8]=SYS_epoll_pwait;
    thread_index[t.pid%THREAD_BUCKETS]=&t; checkpoints.trace=readiness_terminal_trace;
    assert(readiness_native_event(&t,((SIGTRAP|0x80)<<8)|0x7f));
    assert(readiness_terminal_queries==1 && t.resume_lost && t.readiness==&owner);
    assert(!t.terminal_stop && !owner.done && !owner.finishing);
    checkpoints.trace=checkpoint_native_trace; thread_index[t.pid%THREAD_BUCKETS]=NULL;
    puts("PASS readiness terminal replacement: missing syscall snapshot retains exact exit cleanup");
}
static void readiness_signal_return(void) {
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    struct thread t={.pid=123,.ready=1,.born=1,.initial_stop=1,.phase=IDLE,
        .resources={.files=files}};
    assert(task_action_borrowable(&t,NULL));
    struct svc_pool_mapping pool={0};
    t.memory_op.svc_pool=&pool;
    assert(!task_action_borrowable(&t,NULL));
    t.memory_op.svc_pool=NULL;
    const enum phase parked[]={WATCH_READ,IPC_WAITING,SOCKET_WAITING,FILE_WAITING,
        CONTROL_WAITING,CLOSE_WAITING,EPOLL_WAIT};
    for (unsigned i=0;i<sizeof(parked)/sizeof(*parked);++i) {
        t.phase=parked[i];
        assert(task_parked(&t) && freezer_parked(&t) && task_action_borrowable(&t,NULL));
        t.signals.return_pending=1;
        assert(!task_action_borrowable(&t,NULL));
        t.signals.return_pending=0;
        t.interrupt_pending=1;
        assert(!task_action_borrowable(&t,NULL));
        t.interrupt_pending=0;
    }
    const enum phase entering[]={FILE_WAIT_ENTRY,FILE_INTERRUPTING,CONTROL_WAIT_ENTRY,
        CLOSE_WAIT_ENTRY,WAIT_CANCEL_EXIT,DEBUG_PARK_EXIT};
    for (unsigned i=0;i<sizeof(entering)/sizeof(*entering);++i) {
        t.phase=entering[i];
        assert(!task_parked(&t) && !task_action_borrowable(&t,NULL));
    }
    t.phase=IDLE;
    t.signals.return_pending=1;
    assert(!task_action_borrowable(&t,NULL));
    struct task_action owner={.loan_table=files};
    for (unsigned mode=0;mode<3;++mode) {
        owner.requester=mode==1 ? &t : NULL;
        owner.retirement.task=mode==2 ? t.pid : 0;
        int alive=0,stopped=7;
        assert(!loan_borrow(&owner,&t,&alive,&stopped) && alive && stopped==7);
    }
    /* Even a queued carrier must not rewrite an atomic wait's pending mask. */
    siginfo_t signal={.si_signo=SIGUSR2,.si_code=SI_USER};
    assert(!md_signal_delivery_hold(&t.signals.delivery,&signal));
    t.ready=0; t.kernel_stopped=1;
    thread_index[t.pid%THREAD_BUCKETS]=&t; checkpoints.trace=readiness_entry_trace;
    readiness_entry_reads=readiness_entry_resumes=0;
    resume(t.pid,0);
    assert(readiness_entry_resumes==1 && !readiness_entry_reads && !t.kernel_stopped);
    assert(t.signals.return_pending && !t.signals.delivery.active
        && md_signal_delivery_pending(&t.signals.delivery));
    checkpoints.trace=checkpoint_native_trace; thread_index[t.pid%THREAD_BUCKETS]=NULL;
    md_signal_delivery_clear(&t.signals.delivery); md_fd_origins_release(files);
    puts("PASS readiness signal return: no borrowed FD action or carrier before next kernel entry");
}
static void retained_continuation_custody(void) {
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    struct thread t={.pid=123,.ready=1,.born=1,.initial_stop=1,.phase=IDLE,
        .resources={.files=files}};
    struct task_action owner={.loan_table=files};
    const enum phase phases[]={IDLE,EPOLL_WAIT,PROCESS_OBSERVING};
    for (unsigned phase=0;phase<sizeof(phases)/sizeof(*phases);++phase)
        for (unsigned state=0;state<3;++state) for (unsigned mode=0;mode<3;++mode) {
            t.phase=phases[phase]; t.robust.registering=state==0;
            t.robust.terminal=state;
            owner.requester=mode==1 ? &t : NULL;
            owner.retirement.task=mode==2 ? t.pid : 0;
            for (int stopped=0;stopped<2;++stopped) {
                t.kernel_stopped=stopped;
                assert(!task_action_borrowable(&t,owner.requester));
                int alive=0,boundary=7;
                assert(!loan_borrow(&owner,&t,&alive,&boundary) && alive && boundary==7);
                assert(task_action_native_borrow(&owner,&t,&boundary)==-EBUSY && boundary==7);
                assert(!owner.broker_borrowed && t.kernel_stopped==stopped);
            }
            /* A resource becoming ready cannot replace the held lifecycle
             * stop with the preceding selector continuation either. */
            t.descriptors.selector_exit=456; t.kernel_stopped=1;
            thread_index[t.pid%THREAD_BUCKETS]=&t;
            assert(!task_completion_available(t.pid));
            assert(!task_continuation_available(&t));
            retry_wait(&t,RETRY_TASK_ACTION);
            retry_dispatch(0);
            assert(t.descriptors.selector_exit==456 && !t.resume_lost && retained_task_held(&t));
            assert(t.retries[0].mask==RETRY_TASK_ACTION && t.retries[0].node.list);
            retry_clear(&t,RETRY_TASK_ACTION);
            thread_index[t.pid%THREAD_BUCKETS]=NULL;
        }
    t.robust.registering=t.robust.terminal=0;
    assert(task_continuation_available(&t));
    md_fd_origins_release(files);
    puts("PASS retained continuation custody: registration, terminal cleanup and replay exclude every borrowed actor role");
}
static void resident_file_waiter_custody(void) {
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    struct md_fd_order preceding={0};
    assert(md_fd_order_begin(files,&preceding,NULL)==1 && !md_fd_order_start(&preceding));
    struct md_entry_channel channel={0};
    struct thread t={.pid=123,.ready=1,.born=1,.initial_stop=1,.phase=MEMORY_WAIT,
        .resources={.files=files},.entry={.control=&channel}};
    struct readiness_owner readiness={0}; t.readiness=&readiness;
    struct md_fd_order *turns[]={&t.descriptors.order,&t.proc.lookup,&t.memory_op.files,
        &t.metadata.files,&t.fs.descriptor_order,&t.fs.returned.files,&t.proc.delivery.files,&readiness.files};
    for (unsigned i=0;i<sizeof(turns)/sizeof(*turns);++i) {
        assert(!md_fd_order_begin(files,turns[i],NULL));
        assert(task_files_queued(&t) && task_action_borrowable(&t,NULL));
        t.entry.effect=entry_watch_ready; assert(!task_action_borrowable(&t,NULL)); t.entry.effect=NULL;
        t.entry.stop.resume=entry_restart_stopped; assert(!task_action_borrowable(&t,NULL)); t.entry.stop.resume=NULL;
        t.entry.control=NULL; assert(!task_action_borrowable(&t,NULL));
        t.kernel_stopped=1; assert(task_action_borrowable(&t,NULL));
        t.kernel_stopped=0; t.entry.control=&channel;
        assert(!md_fd_order_cancel(turns[i]));
    }
    assert(!task_action_borrowable(&t,NULL));
    assert(!md_fd_order_complete(&preceding)); md_fd_origins_release(files);
    puts("PASS resident file waiters: queued ownership admits exact borrowed continuation across descriptor, memory, metadata, transfer and readiness owners");
}
static unsigned readiness_interrupted_step;
static long readiness_interrupted_trace(int request,pid_t pid,void *address,void *data) {
    assert(pid==123);
    struct thread *t=find_thread(pid); assert(t);
    switch (readiness_interrupted_step++) {
    case 0:
        assert(request==PTRACE_GETREGSET && (uintptr_t)address==NT_PRSTATUS);
        assert(((struct iovec *)data)->iov_len==sizeof(long));
        *(long *)((struct iovec *)data)->iov_base=-514;
        break;
    case 1: {
        assert(request==PTRACE_SETREGSET && (uintptr_t)address==NT_PRSTATUS);
        struct user_pt_regs expected=t->execution.registers; expected.regs[0]=(uint64_t)-EINTR;
        assert(!memcmp(((struct iovec *)data)->iov_base,&expected,sizeof(expected)));
        assert(t->signals.return_pending && !t->readiness);
        break;
    }
    case 2:
        assert(request==PTRACE_SYSCALL && !address && !data);
        assert(t->signals.return_pending && t->phase==IDLE && !t->signals.shielded);
        break;
    default: assert(!"unexpected mask change or injected call after interrupted wait");
    }
    return 0;
}
static void readiness_parked_signal_return(void) {
    struct thread t={.pid=123,.phase=SOCKET_WAITING,.kernel_stopped=1,
        .memory=md_memory_new(),.readiness=calloc(1,sizeof(struct readiness_owner))};
    assert(t.memory && t.readiness);
    t.execution.registers=(struct user_pt_regs){.pc=0x1004,.regs={7,0x2000,3,4000,0,8}};
    t.execution.registers.regs[8]=SYS_epoll_pwait;
    execution_capture(&t,&t.execution.registers);
    readiness_interrupted_step=0;
    thread_index[t.pid%THREAD_BUCKETS]=&t; checkpoints.trace=readiness_interrupted_trace;
    socket_wait_complete(&t);
    assert(readiness_interrupted_step==3 && !t.kernel_stopped && t.signals.return_pending);
    checkpoints.trace=checkpoint_native_trace; thread_index[t.pid%THREAD_BUCKETS]=NULL;
    md_memory_release(t.memory);
    puts("PASS readiness parked signal return: original EINTR, no mask rewrite and deferred cleanup");
}
static long readiness_cancel_error;
static long readiness_cancel_trace(int request,pid_t pid,void *address,void *data) {
    assert(pid==123);
    switch (readiness_cancel_step++) {
    case 0: {
        assert(request==PTRACE_GETREGSET && (uintptr_t)address==NT_PRSTATUS);
        struct user_pt_regs *regs=((struct iovec *)data)->iov_base;
        *regs=(struct user_pt_regs){.pc=abi.task_return};
        break;
    }
    case 1: {
        assert(request==PTRACE_GET_SYSCALL_INFO);
        struct ptrace_syscall_info *info=data;
        *info=(struct ptrace_syscall_info){.op=PTRACE_SYSCALL_INFO_ENTRY,
            .instruction_pointer=abi.task_return,.entry={.nr=SYS_epoll_pwait}};
        break;
    }
    case 2:
        assert(request==PTRACE_SETREGSET && (uintptr_t)address==NT_ARM_SYSTEM_CALL
            && *(int *)((struct iovec *)data)->iov_base==-1);
        break;
    case 3:
        assert(request==PTRACE_SYSCALL && !address && !data);
        break;
    case 4:
        assert(request==PTRACE_GET_SYSCALL_INFO);
        *(struct ptrace_syscall_info *)data=(struct ptrace_syscall_info){.op=PTRACE_SYSCALL_INFO_EXIT};
        break;
    case 5:
        assert(request==PTRACE_SETREGSET && (uintptr_t)address==NT_PRSTATUS);
        assert((long)((struct user_pt_regs *)((struct iovec *)data)->iov_base)->regs[0]==readiness_cancel_error);
        break;
    case 6:
        assert(request==PTRACE_CONT && !address && !data);
        break;
    default: assert(!"unexpected readiness cancellation request");
    }
    return 0;
}
static void readiness_cancel_entry(void) {
    struct readiness_owner owner={.evicting=1,.native_call={.number=SYS_epoll_pwait}};
    struct thread t={.pid=123,.phase=EPOLL_CALL,.kernel_stopped=1,.readiness=&owner};
    thread_index[t.pid%THREAD_BUCKETS]=&t;
    checkpoints.trace=readiness_cancel_trace;
    fd_ready=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK); assert(fd_ready>=0);
    int status=((SIGTRAP|0x80)<<8)|0x7f;
    assert(readiness_native_event(&t,status));
    assert(t.phase==WAIT_CANCEL_EXIT && t.execution.wait_resume==EPOLL_CALL && !owner.parked);
    assert(readiness_cancel_step==4);
    uint64_t events;
    assert(read(fd_ready,&events,sizeof(events))==-1 && errno==EAGAIN);
    /* A pending signal/interrupt cannot publish a still-entering wait. */
    assert(!readiness_native_event(&t,(PTRACE_EVENT_STOP<<16)|(SIGTRAP<<8)|0x7f));
    assert(!owner.parked && readiness_cancel_step==4);
    t.kernel_stopped=1;
    assert(readiness_native_event(&t,status));
    assert(owner.parked && t.phase==DISPATCHING && t.execution.wait_resume==IDLE && readiness_cancel_step==5);
    assert(read(fd_ready,&events,sizeof(events))==sizeof(events) && events==1);
    /* A failed observer registration has the same entry/exit fence, but
     * returns its error instead of parking for another file-table owner. */
    int previous_socket_events=socket_events;
    socket_events=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK); assert(socket_events>=0);
    struct md_control_epoll *queue=md_control_epoll_new(); assert(queue);
    assert(md_control_epoll_admit_begin(queue)==1 && !md_control_epoll_admit_finish(queue,1));
    owner=(struct readiness_owner){.native_call={.number=SYS_epoll_pwait},.wait={.blocking=1},
        .queue=md_control_epoll_object(queue)};
    t.phase=EPOLL_CALL; t.kernel_stopped=1; readiness_cancel_step=0;
    assert(readiness_native_event(&t,status));
    assert(t.phase==WAIT_CANCEL_EXIT && t.execution.wait_resume==EPOLL_CALL && owner.finishing && !owner.done);
    readiness_cancel_error=owner.finish_result; assert(readiness_cancel_error<0);
    assert(readiness_cancel_step==4 && !owner.parked);
    assert(read(fd_ready,&events,sizeof(events))==-1 && errno==EAGAIN);
    t.kernel_stopped=1;
    assert(readiness_native_event(&t,status));
    assert(owner.done && !owner.parked && t.phase==DISPATCHING && t.execution.wait_resume==IDLE);
    assert(readiness_cancel_step==7);
    assert(read(fd_ready,&events,sizeof(events))==-1 && errno==EAGAIN);
    assert(!owner.wait.job.started);
    md_control_epoll_release(queue);
    close(socket_events); socket_events=previous_socket_events;
    close(fd_ready); fd_ready=-1;
    checkpoints.trace=checkpoint_native_trace;
    thread_index[t.pid%THREAD_BUCKETS]=NULL;
    puts("PASS epoll loan revocation: skipped entry drains before descriptor retirement or adapter reply");
}
static long duplicate_reply;
static long duplicate_return_trace(int request,pid_t pid,void *address,void *data) {
    assert(request==PTRACE_SETREGSET && pid==123 && (uintptr_t)address==NT_PRSTATUS);
    struct user_pt_regs *regs=((struct iovec *)data)->iov_base;
    assert((long)regs->regs[0]==duplicate_reply);
    errno=ESRCH; return -1;
}
static void duplicate_retirement_order(void) {
    for (unsigned owned=0;owned<2;++owned) {
        struct md_fd_origins *files=md_fd_origins_new(); assert(files);
        struct md_fd_observation view;
        assert(!md_fd_origins_observe(files,5,&view));
        struct thread t={.pid=123,.descriptors.job=MD_FD_DUPLICATE_BEGIN};
        t.resources.files=files; t.execution.request.args[0]=5; t.execution.request.nr=SYS_dup3;
        t.descriptors.request=(struct supervisor_control_request){.command=MD_FD_DUPLICATE_BEGIN,
            .args={view.generation}};
        assert(md_fd_order_begin(files,&t.descriptors.order,NULL)==1);
        assert(!md_fd_order_start(&t.descriptors.order));
        assert(!md_fd_origins_validate(&t.descriptors.order,view.generation));
        assert(!md_fd_origins_close_range(files,10,10,0));
        assert(md_fd_origins_validate(&t.descriptors.order,view.generation)==-EAGAIN);
        thread_index[t.pid%THREAD_BUCKETS]=&t;
        checkpoints.trace=duplicate_return_trace;
        duplicate_reply=owned ? 0 : -EAGAIN;
        if (owned) fd_loan_closed(&t,0);
        else fd_start(&t);
        checkpoints.trace=checkpoint_native_trace;
        thread_index[t.pid%THREAD_BUCKETS]=NULL;
        assert(t.resume_lost && !t.retries[0].mask && !t.retries[1].mask);
        if (owned) {
            assert(t.descriptors.job==MD_FD_DUPLICATE_BEGIN);
            assert(!md_fd_origins_validate(&t.descriptors.order,t.descriptors.request.args[0]));
            assert(!md_fd_order_complete(&t.descriptors.order));
        } else assert(!t.descriptors.job && !t.descriptors.order.table);
        md_fd_origins_release(files);
    }
    puts("PASS duplicate admission: foreign generation retries, owned loan retirement retains its table turn");
}
static long no_trace(int request,pid_t pid,void *address,void *data) {
    (void)request; (void)pid; (void)address; (void)data;
    assert(!"deferring a repair must not stop unrelated tasks"); return -1;
}
static void orphan_loan_retirement(void) {
    assert(!threads && !loan_retirements && !task_action_calls);
    int previous_fd_ready=fd_ready;
    fd_ready=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK); assert(fd_ready>=0);
    checkpoints.trace=no_trace;
    for (unsigned copied=0;copied<2;++copied) for (unsigned ended=0;ended<2;++ended) {
        if (!copied && !ended) continue;
        struct md_fd_origins *home=md_fd_origins_new(); assert(home);
        struct md_fd_loan *loan=md_fd_loan_new(home); assert(loan);
        struct md_fd_origin_update update;
        assert(!md_fd_origins_prepare(home,NULL,FD_CLOEXEC,&update));
        assert(!md_fd_origins_prepare_loan(&update,loan));
        md_fd_origins_finish(&update,14);
        struct md_fd_origins *files=copied ? md_fd_origins_fork(home,0) : home;
        assert(files);
        if (ended) md_fd_loan_end(loan);
        struct task_action parent_retirement={.loan=loan,.loan_table=home,.number=14};
        struct readiness_owner parent_wait={.loans={{.retirement=&parent_retirement,.number=14},{.number=-1}}};
        struct thread parent_task={.pid=122,.phase=EPOLL_WAIT,.readiness=&parent_wait};
        parent_task.resources.files=home;
        if (copied && !ended) threads=&parent_task;
        struct thread t={.pid=123}; t.resources.files=files;
        t.descriptors.call=(struct seccomp_data){.nr=SYS_close,.args={14}};
        assert(md_fd_order_begin(files,&t.descriptors.order,NULL)==1);
        assert(!md_fd_order_start(&t.descriptors.order));
        assert(!fd_loans_ready(&t));
        struct task_action *action=t.action;
        assert(action && loan_retirements==action && task_action_calls==1);
        assert(action->loan==loan && action->loan_table==files && action->number==14);
        assert(action->loan_turn==&t.descriptors.order && action->complete==fd_loan_closed);
        assert(md_fd_order_state(&t.descriptors.order)==MD_RESOURCE_ORDER_RUNNING);
        assert(!parent_wait.evicting && parent_wait.loans[0].retirement==&parent_retirement);
        assert(!md_fd_origins_close_range(files,14,14,0));
        t.action=NULL; task_action_release(action);
        assert(fd_loans_ready(&t));
        assert(!md_fd_order_complete(&t.descriptors.order));
        if (copied) {
            struct md_fd_order parent={0}; int number=-1;
            assert(md_fd_order_begin(home,&parent,NULL)==1 && !md_fd_order_start(&parent));
            assert(md_fd_origins_loan_matches(&parent,14,loan)==1);
            assert(md_fd_origins_loan_next(&parent,&number)==(int)ended);
            assert(!md_fd_order_complete(&parent));
            md_fd_origins_release(files);
        }
        md_fd_loan_end(loan); md_fd_loan_release(loan); md_fd_origins_release(home);
        threads=NULL;
        assert(!loan_retirements && !task_action_calls);
    }
    checkpoints.trace=checkpoint_native_trace;
    close(fd_ready); fd_ready=previous_fd_ready;
    puts("PASS orphan loan retirement: expired and fork-copied slots retain table order without revoking the parent");
}
static unsigned resident_loan_completions;
static void resident_loan_complete(struct thread *t,long result) {
    assert(!result && !t->action && !t->entry.effect);
    ++resident_loan_completions;
}
static void resident_loan_retirement(void) {
    int previous_fd_ready=fd_ready;
    fd_ready=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK); assert(fd_ready>=0);
    checkpoints.trace=no_trace;
    const long results[]={0,-EINTR,-EIO,-EBADF,-EPERM,-EACCES,-ENOSYS};
    for (unsigned borrowed=0;borrowed<2;++borrowed)
    for (unsigned i=0;i<sizeof(results)/sizeof(*results);++i) {
        assert(!threads && !loan_retirements && !task_action_calls);
        struct md_fd_origins *files=md_fd_origins_new(); assert(files);
        struct md_fd_loan *loan=md_fd_loan_new(files); assert(loan);
        struct md_fd_origin_update update;
        assert(!md_fd_origins_prepare(files,NULL,FD_CLOEXEC,&update));
        assert(!md_fd_origins_prepare_loan(&update,loan)); md_fd_origins_finish(&update,14);
        struct md_entry_channel channel={0};
        struct thread t={.pid=123,.tgid=123,.entry={.control=&channel}};
        t.resources.files=files; thread_index[t.pid%THREAD_BUCKETS]=&t;
        struct task_action *owner=calloc(1,sizeof(*owner)); assert(owner);
        owner->loan=loan; owner->loan_table=md_fd_origins_pin(files); owner->number=14;
        struct md_fd_order turn={0};
        if (borrowed) assert(md_fd_order_begin(files,&turn,NULL)==1 && !md_fd_order_start(&turn));
        resident_loan_completions=0;
        loan_retire(owner,&t,borrowed ? &turn : NULL,resident_loan_complete);
        task_action_schedule();
        assert(owner->resident && t.entry.effect==loan_entry_complete && !task_action_execution);
        assert(channel.native.number==SYS_close && channel.native.args[0]==14);
        task_action_schedule(); assert(owner->resident && !resident_loan_completions);
        /* Withdrawn plans are retried under the same turn, not committed as
         * close(-EINTR), which would retire a kernel descriptor. */
        channel.call.reserved=MD_ENTRY_EFFECT_DONE; channel.native.result=-EINTR;
        atomic_store(&channel.native.state,MD_NATIVE_CANCELLED);
        entry_control(t.pid,&channel);
        assert(owner->resident && atomic_load(&channel.native.state)==MD_NATIVE_READY);
        assert(md_fd_origins_loan_matches(borrowed ? &turn : &owner->files,14,loan)==1);
        channel.native.result=results[i]; atomic_store(&channel.native.state,MD_NATIVE_DONE);
        entry_control(t.pid,&channel);
        if (i>=4) {
            assert(owner->resident && channel.native.number==SYS_close_range
                && channel.native.args[0]==14 && channel.native.args[1]==14 && !channel.native.args[2]);
            channel.native.result=0; atomic_store(&channel.native.state,MD_NATIVE_DONE);
            entry_control(t.pid,&channel);
        }
        assert(resident_loan_completions==1 && !t.action && !loan_retirements && !task_action_calls);
        if (!borrowed) assert(md_fd_order_begin(files,&turn,NULL)==1 && !md_fd_order_start(&turn));
        int remaining=-1;
        assert(!md_fd_origins_loan_next(&turn,&remaining) && remaining==-1);
        assert(!md_fd_order_complete(&turn));
        thread_index[t.pid%THREAD_BUCKETS]=NULL; md_fd_origins_release(files);
    }
    checkpoints.trace=checkpoint_native_trace;
    close(fd_ready); fd_ready=previous_fd_ready;
    puts("PASS resident loan retirement: shared turn, cancellation, EINTR, EBADF and close_range fallback without ptrace");
}
static void resident_loan_abandonment(void) {
    int previous_fd_ready=fd_ready;
    fd_ready=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK); assert(fd_ready>=0);
    checkpoints.trace=no_trace;
    const unsigned states[]={MD_NATIVE_READY,MD_NATIVE_CANCELLED,MD_NATIVE_DONE,MD_NATIVE_RUNNING,MD_NATIVE_UNKNOWN};
    for (unsigned i=0;i<sizeof(states)/sizeof(*states);++i) {
        struct md_fd_origins *files=md_fd_origins_new(); assert(files);
        struct md_fd_loan *loan=md_fd_loan_new(files); assert(loan);
        struct md_fd_origin_update update;
        assert(!md_fd_origins_prepare(files,NULL,FD_CLOEXEC,&update));
        assert(!md_fd_origins_prepare_loan(&update,loan)); md_fd_origins_finish(&update,14);
        struct md_entry_channel channel={0};
        struct thread t={.pid=123,.entry={.control=&channel}}; t.resources.files=files;
        struct task_action *owner=calloc(1,sizeof(*owner)); assert(owner);
        owner->loan=loan; owner->loan_table=md_fd_origins_pin(files); owner->number=14;
        assert(md_fd_order_begin(files,&t.descriptors.order,NULL)==1 && !md_fd_order_start(&t.descriptors.order));
        loan_retire(owner,&t,&t.descriptors.order,resident_loan_complete); loan_start(owner);
        channel.native.result=0; atomic_store(&channel.native.state,states[i]);
        fd_discard(&t);
        assert(!t.action && !t.entry.effect && !owner->resident && !owner->loan_turn && !owner->requester);
        assert(!t.descriptors.order.table);
        struct md_fd_order turn={0};
        assert(md_fd_order_begin(files,&turn,NULL)==1 && !md_fd_order_start(&turn));
        int remaining=md_fd_origins_loan_matches(&turn,14,loan);
        assert(remaining==(i<2 ? 1 : i==2 ? 0 : -ESTALE));
        assert(!md_fd_order_complete(&turn)); task_action_release(owner);
        md_fd_origins_release(files);
        assert(!task_action_calls && !loan_retirements);
    }
    checkpoints.trace=checkpoint_native_trace;
    close(fd_ready); fd_ready=previous_fd_ready;
    puts("PASS resident loan cancellation: receipt before borrowed-turn release, withdrawn plans and uncertain effects never replayed");
}
static void queued_loan_retirement(void) {
    int previous_fd_ready=fd_ready;
    fd_ready=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK); assert(fd_ready>=0);
    checkpoints.trace=no_trace;
    for (unsigned copied=0;copied<2;++copied) {
        struct md_fd_origins *home=md_fd_origins_new(); assert(home);
        struct md_fd_loan *loan=md_fd_loan_new(home); assert(loan);
        struct md_fd_origin_update update;
        assert(!md_fd_origins_prepare(home,NULL,FD_CLOEXEC,&update));
        assert(!md_fd_origins_prepare_loan(&update,loan)); md_fd_origins_finish(&update,14);
        struct thread t={.pid=123};
        t.resources.files=copied ? md_fd_origins_fork(home,0) : home; assert(t.resources.files);
        t.descriptors.call=(struct seccomp_data){.nr=SYS_close,.args={14}};
        assert(md_fd_order_begin(t.resources.files,&t.descriptors.order,NULL)==1);
        assert(!md_fd_order_start(&t.descriptors.order));
        struct task_action *prior=calloc(1,sizeof(*prior)); assert(prior);
        prior->loan=loan; prior->loan_table=md_fd_origins_pin(home); prior->number=14;
        loan_retire(prior,NULL,NULL,NULL);
        assert(!fd_loans_ready(&t));
        if (copied) {
            assert(t.action && t.action!=prior && task_action_calls==2);
            assert(md_fd_order_state(&t.descriptors.order)==MD_RESOURCE_ORDER_RUNNING);
            struct task_action *local=t.action; t.action=NULL; task_action_release(local);
            md_fd_origins_release(t.resources.files);
        } else {
            assert(!t.action && task_action_calls==1);
            assert(md_fd_order_state(&prior->files)==MD_RESOURCE_ORDER_READY);
            assert(md_fd_order_state(&t.descriptors.order)==MD_RESOURCE_ORDER_QUEUED);
            retry_clear(&t,RETRY_FD);
        }
        task_action_release(prior);
        if (md_fd_order_state(&t.descriptors.order)==MD_RESOURCE_ORDER_READY) assert(!md_fd_order_start(&t.descriptors.order));
        assert(!md_fd_order_complete(&t.descriptors.order));
        assert(!loan_retirements && !task_action_calls);
        md_fd_origins_release(home);
    }
    checkpoints.trace=checkpoint_native_trace;
    close(fd_ready); fd_ready=previous_fd_ready;
    puts("PASS queued loan retirement: same-table close wins, copied-table close remains independent");
}
static int fd_expected_resume, fd_resume_calls, fd_test_operation, fd_test_mode;
static long fd_resume_trace(int request,pid_t pid,void *address,void *data) {
    if (request!=fd_expected_resume || pid!=123 || address || data)
        fprintf(stderr,"FD resume op=%d mode=%d expected=%d actual=%d pid=%d address=%p data=%p\n",
            fd_test_operation,fd_test_mode,fd_expected_resume,request,pid,address,data);
    assert(request==fd_expected_resume && pid==123 && !address && !data);
    ++fd_resume_calls; return 0;
}
static unsigned signal_delivery_queries,signal_delivery_resumes;
static int signal_delivery_number;
static long signal_delivery_trace(int request,pid_t pid,void *address,void *data) {
    assert(pid==123);
    if (request==PTRACE_GETSIGMASK) {
        assert(address==(void *)sizeof(uint64_t));
        *(uint64_t *)data=UINT64_C(1)<<(SIGUSR2-1);
        ++signal_delivery_queries; return 0;
    }
    assert(request==PTRACE_CONT && !address && data==(void *)(uintptr_t)signal_delivery_number);
    ++signal_delivery_resumes; return 0;
}
static void exclusive_signal_entry(void) {
    debugger=md_debugger_create(&(struct md_debugger_host){0}); assert(debugger);
    for (unsigned reset=0;reset<2;++reset) for (unsigned sys=0;sys<2;++sys)
            for (unsigned blocked=0;blocked<2;++blocked) for (unsigned nodefer=0;nodefer<2;++nodefer) {
        struct thread t={.pid=123,.phase=IDLE,.kernel_stopped=1,.entered=1,.signals.host=1,
            .memory=md_memory_new(),.signals.actions=md_signal_actions_new()};
        assert(t.memory && t.signals.actions);
        signal_delivery_number=sys ? SIGSYS : SIGALRM;
        struct md_kernel_action action={.handler=0x1234,
            .mask=(UINT64_C(1)<<(SIGUSR2-1))|(blocked ? MD_SIGNAL_SYS_BIT : 0),
            .flags=(reset ? SA_RESETHAND : 0)|(nodefer ? SA_NODEFER : 0)};
        assert(!md_signal_actions_commit(t.signals.actions,signal_delivery_number,&action));
        assert(signal_frame_native(&t));
        t.signals.host_blocked=1; assert(!signal_frame_native(&t));
        t.signals.host_blocked=0; t.signals.wait_view.active=1;
        assert(!signal_frame_native(&t));
        t.signals.wait_view.active=0;
        signal_delivery_queries=signal_delivery_resumes=0;
        checkpoints.trace=signal_delivery_trace; thread_index[t.pid%THREAD_BUCKETS]=&t;
        assert(signal_control_delivery(&t,signal_delivery_number,NULL));
        assert(signal_delivery_queries==1 && signal_delivery_resumes==1 && !t.signals.control
            && t.phase==IDLE && !t.kernel_stopped && t.signals.host_blocked==!!(blocked || (sys && !nodefer)));
        assert(!md_signal_actions_read(t.signals.actions,signal_delivery_number,&action));
        assert(action.handler==(reset ? (uintptr_t)SIG_DFL : 0x1234));
        checkpoints.trace=checkpoint_native_trace; thread_index[t.pid%THREAD_BUCKETS]=NULL;
        md_signal_actions_release(t.signals.actions); md_memory_release(t.memory);
    }
    md_debugger_destroy(debugger); debugger=NULL;
    puts("PASS exclusive signal entry: native frame delivery without an interrupt or register rewrite");
}
static uint64_t signal_query_mask;
static unsigned signal_query_reads,signal_query_writes,signal_query_calls,signal_query_returns;
static long signal_query_trace(int request,pid_t pid,void *address,void *data) {
    assert(pid==123);
    if (request==PTRACE_GETSIGMASK || request==PTRACE_SETSIGMASK) {
        assert(address==(void *)sizeof(uint64_t));
        if (request==PTRACE_GETSIGMASK) { *(uint64_t *)data=signal_query_mask; ++signal_query_reads; }
        else { signal_query_mask=*(uint64_t *)data; ++signal_query_writes; }
        return 0;
    }
    assert(!address && !data);
    if (request==PTRACE_SYSCALL) ++signal_query_calls;
    else { assert(request==PTRACE_CONT); ++signal_query_returns; }
    return 0;
}
static void native_signal_query(void) {
    debugger=md_debugger_create(&(struct md_debugger_host){0}); assert(debugger);
    struct thread eligible={.phase=IDLE,.signals.host=1};
    assert(signal_query_native(&eligible));
    eligible.signals.host_blocked=1; assert(!signal_query_native(&eligible)); eligible.signals.host_blocked=0;
    eligible.signals.shielded=1; assert(!signal_query_native(&eligible)); eligible.signals.shielded=0;
    eligible.signals.delivery.active=1; assert(!signal_query_native(&eligible)); eligible.signals.delivery.active=0;
    eligible.phase=DISPATCHING; assert(!signal_query_native(&eligible)); eligible.phase=IDLE;
    eligible.debug.call_active=1; assert(!signal_query_native(&eligible)); eligible.debug.call_active=0;
    assert(signal_query_native(&eligible));
    for (unsigned blocked=0;blocked<2;++blocked) for (unsigned output=0;output<2;++output)
            for (unsigned input=0;input<2;++input) {
        struct thread t={.pid=123,.phase=IDLE,.kernel_stopped=1,.entered=1,.signals.host=1,
            .signals.host_blocked=blocked,.memory=md_memory_new()};
        assert(t.memory);
        uint64_t before=UINT64_C(1)<<(SIGUSR2-1);
        signal_query_mask=before;
        signal_query_reads=signal_query_writes=signal_query_calls=signal_query_returns=0;
        checkpoints.trace=signal_query_trace; thread_index[t.pid%THREAD_BUCKETS]=&t;
        assert(signal_native_call(&t,SYS_rt_sigprocmask,input,output,8));
        unsigned expected_blocked=blocked;
        if (input || (blocked && output)) {
            assert(t.phase==SIGNAL_NATIVE_EXIT && signal_query_mask==(before|(blocked ? MD_SIGNAL_SYS_BIT : 0))
                && signal_query_reads==blocked && signal_query_writes==blocked && signal_query_calls==1
                && !signal_query_returns);
            if (input) {
                before|=UINT64_C(1)<<(SIGUSR1-1);
                expected_blocked=!blocked;
                signal_query_mask=before|(expected_blocked ? MD_SIGNAL_SYS_BIT : 0);
            }
            t.kernel_stopped=1;
            signal_native_complete(&t);
            assert(signal_query_reads==blocked+input && signal_query_writes==blocked+expected_blocked);
        } else assert(!signal_query_reads && !signal_query_writes && !signal_query_calls);
        assert(t.phase==IDLE && signal_query_returns==1 && signal_query_mask==before
            && t.signals.host_blocked==(int)expected_blocked && !t.signals.control && !t.signals.query_active);
        checkpoints.trace=checkpoint_native_trace; thread_index[t.pid%THREAD_BUCKETS]=NULL;
        md_memory_release(t.memory);
    }
    struct thread pending={.pid=123,.phase=SIGNAL_NATIVE_EXIT,.kernel_stopped=1,
        .entered=1,.signals.host=1,.signals.delivery={.active=1},.memory=md_memory_new()};
    assert(pending.memory);
    signal_query_calls=signal_query_returns=0;
    checkpoints.trace=signal_query_trace; thread_index[pending.pid%THREAD_BUCKETS]=&pending;
    resume(pending.pid,0);
    assert(signal_query_calls==1 && !signal_query_returns && !pending.kernel_stopped);
    checkpoints.trace=checkpoint_native_trace; thread_index[pending.pid%THREAD_BUCKETS]=NULL;
    md_memory_release(pending.memory);
    md_debugger_destroy(debugger); debugger=NULL;
    puts("PASS native signal masks: query and mutation at admitted syscall, restored before application execution");
}
static unsigned signal_frame_interrupts,signal_frame_deliveries;
static struct user_pt_regs query_completed;
static unsigned query_register_reads,query_register_writes,query_resumes;
static long direct_query_trace(int request,pid_t pid,void *address,void *data) {
    assert(pid==getpid());
    if (request==PTRACE_GETSIGMASK) {
        assert(address==(void *)sizeof(uint64_t));
        *(uint64_t *)data=UINT64_C(1)<<(SIGUSR2-1); return 0;
    }
    if (request==PTRACE_GETREGSET) {
        assert(address==(void *)NT_PRSTATUS);
        *(struct user_pt_regs *)((struct iovec *)data)->iov_base=
            (struct user_pt_regs){.pc=0x1004,.regs={[8]=SYS_rt_sigprocmask}};
        ++query_register_reads; return 0;
    }
    if (request==PTRACE_SETREGSET) {
        struct iovec *io=data;
        if (address==(void *)NT_ARM_SYSTEM_CALL) assert(*(int *)io->iov_base==-1);
        else {
            assert(address==(void *)NT_PRSTATUS);
            assert(io->iov_len==sizeof(uint64_t));
            memcpy(&query_completed,io->iov_base,io->iov_len);
        }
        ++query_register_writes; return 0;
    }
    assert(request==PTRACE_CONT && !address && !data); ++query_resumes; return 0;
}
static void direct_signal_query(void) {
    debugger=md_debugger_create(&(struct md_debugger_host){0}); assert(debugger);
    struct thread t={.pid=getpid(),.phase=IDLE,.kernel_stopped=1,.entered=1,.signals.host=1,
        .signals.host_blocked=1,.memory=md_memory_new()}; assert(t.memory);
    uint64_t *output=mmap(NULL,getpagesize(),PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(output!=MAP_FAILED);
    thread_index[t.pid%THREAD_BUCKETS]=&t; checkpoints.trace=direct_query_trace;
    for (unsigned fault=0;fault<2;++fault) {
        if (fault) assert(!mprotect(output,getpagesize(),PROT_READ));
        query_register_reads=query_register_writes=query_resumes=0; t.kernel_stopped=1;
        assert(signal_native_call(&t,SYS_rt_sigprocmask,0,(uintptr_t)output,8));
        assert(t.phase==IDLE && !t.signals.query_active && !t.kernel_stopped && t.signals.host_blocked);
        assert(query_register_reads==0 && query_register_writes==2 && query_resumes==1);
        assert((long)query_completed.regs[0]==(fault ? -EFAULT : 0));
        assert(*output==((UINT64_C(1)<<(SIGUSR2-1))|MD_SIGNAL_SYS_BIT));
    }
    assert(!munmap(output,getpagesize()));
    thread_index[t.pid%THREAD_BUCKETS]=NULL; checkpoints.trace=checkpoint_native_trace;
    md_memory_release(t.memory); md_debugger_destroy(debugger); debugger=NULL;
    puts("PASS direct signal query: kernel-checked copy and EFAULT, no mask loan or guest exit stop");
}
static long signal_frame_trace(int request,pid_t pid,void *address,void *data) {
    if (request==PTRACE_INTERRUPT || (request==PTRACE_CONT && data==(void *)(uintptr_t)SIGALRM)) {
        assert(pid==123 && !address);
        if (request==PTRACE_INTERRUPT) { assert(!data); ++signal_frame_interrupts; }
        else ++signal_frame_deliveries;
        return 0;
    }
    return signal_query_trace(request,pid,address,data);
}
static void logical_signal_frame(void) {
    debugger=md_debugger_create(&(struct md_debugger_host){0}); assert(debugger);
    for (unsigned blocked=0;blocked<2;++blocked) for (unsigned action_bit=0;action_bit<2;++action_bit) {
        struct thread t={.pid=123,.phase=IDLE,.kernel_stopped=1,.entered=1,.signals.host=1,
            .signals.host_blocked=blocked,.memory=md_memory_new(),.signals.actions=md_signal_actions_new()};
        assert(t.memory && t.signals.actions);
        struct md_signal_actions *peer=md_signal_actions_fork(t.signals.actions,1,0); assert(peer);
        uint64_t before=UINT64_C(1)<<(SIGUSR2-1);
        struct md_kernel_action action={.handler=0x1234,.mask=action_bit ? MD_SIGNAL_SYS_BIT : 0};
        assert(!md_signal_actions_commit(t.signals.actions,SIGALRM,&action));
        signal_query_mask=before;
        signal_query_reads=signal_query_writes=signal_query_calls=signal_query_returns=0;
        signal_frame_interrupts=signal_frame_deliveries=0;
        checkpoints.trace=signal_frame_trace; thread_index[t.pid%THREAD_BUCKETS]=&t;
        assert(signal_control_delivery(&t,SIGALRM,NULL));
        assert(t.phase==SIGNAL_ENTRY && t.signals.control && !t.signals.control->scratch.pool
            && signal_query_reads==1 && signal_query_writes==blocked
            && signal_query_mask==(before|(blocked ? MD_SIGNAL_SYS_BIT : 0))
            && signal_frame_interrupts==1 && signal_frame_deliveries==1);
        signal_query_mask|=UINT64_C(1)<<(SIGALRM-1);
        t.kernel_stopped=1;
        assert(signal_control_event(&t,(PTRACE_EVENT_STOP<<16)|(SIGTRAP<<8)|0x7f));
        assert(t.phase==IDLE && !t.signals.control && !t.kernel_stopped
            && t.signals.host_blocked==!!(blocked|action_bit)
            && signal_query_mask==(before|(UINT64_C(1)<<(SIGALRM-1)))
            && signal_query_reads==1 && signal_query_writes==2*blocked
            && !signal_query_calls && signal_query_returns==1);
        checkpoints.trace=checkpoint_native_trace; thread_index[t.pid%THREAD_BUCKETS]=NULL;
        md_signal_actions_release(peer); md_signal_actions_release(t.signals.actions); md_memory_release(t.memory);
    }
    md_debugger_destroy(debugger); debugger=NULL;
    puts("PASS logical signal frame: kernel mask publication, no buffer or register copy, native handler mask restored");
}
static void native_signal_entry(void) {
    for (unsigned reset=0;reset<2;++reset) {
        struct thread t={.pid=123,.phase=SIGNAL_ENTRY,.kernel_stopped=1,
            .memory=md_memory_new(),.signals.actions=md_signal_actions_new()};
        assert(t.memory && t.signals.actions);
        struct signal_control *owner=calloc(1,sizeof(*owner)); assert(owner);
        t.signals.control=owner; owner->started=1; owner->signal=SIGALRM;
        owner->action=(struct md_kernel_action){.handler=0x1234,
            .mask=UINT64_C(1)<<(SIGUSR2-1),.flags=reset ? SA_RESETHAND : 0};
        assert(!md_signal_actions_commit(t.signals.actions,SIGALRM,&owner->action));
        assert(md_memory_order_begin(t.memory,&owner->memory,NULL)==1);
        assert(!md_resource_order_start(&owner->memory));
        fd_expected_resume=PTRACE_CONT; fd_resume_calls=0;
        checkpoints.trace=fd_resume_trace; thread_index[t.pid%THREAD_BUCKETS]=&t;
        assert(signal_control_event(&t,(PTRACE_EVENT_STOP<<16)|(SIGTRAP<<8)|0x7f));
        assert(fd_resume_calls==1 && !t.signals.control && t.phase==IDLE
            && !t.kernel_stopped && !t.signals.host_blocked);
        struct md_kernel_action action;
        assert(!md_signal_actions_read(t.signals.actions,SIGALRM,&action));
        assert(action.handler==(reset ? (uintptr_t)SIG_DFL : 0x1234));
        struct md_resource_order next={0};
        assert(md_memory_order_begin(t.memory,&next,NULL)==1);
        assert(!md_resource_order_start(&next) && !md_resource_order_complete(&next));
        checkpoints.trace=checkpoint_native_trace; thread_index[t.pid%THREAD_BUCKETS]=NULL;
        md_signal_actions_release(t.signals.actions); md_memory_release(t.memory);
    }
    puts("PASS native signal entry: no register or mask rewrite, one-shot action reset and released admission");
}
static unsigned pending_signal_interrupts;
static long pending_signal_trace(int request,pid_t pid,void *address,void *data) {
    assert(request==PTRACE_INTERRUPT && pid==123 && !address && !data);
    ++pending_signal_interrupts;
    return 0;
}
static void pending_signal_wakeup(void) {
    checkpoints.trace=pending_signal_trace;
    for (unsigned state=0;state<4;++state) for (unsigned held=0;held<4;++held) {
        struct md_signal_entry packet={.pending=1};
        struct signal_control owner={.returning=state==2,.entry_packet=state==1 ? &packet : NULL};
        struct thread t={.pid=123,.signals.control=state ? &owner : NULL,
            .signals.host_blocked=held==1,.kernel_stopped=held==2,.interrupt_pending=held==3};
        pending_signal_interrupts=0;
        assert(!signal_pending_wake(&t));
        assert(pending_signal_interrupts==(unsigned)(state!=3 && !held));
        assert(!signal_pending_wake(&t));
        assert(pending_signal_interrupts==(unsigned)(state!=3 && !held));
    }
    checkpoints.trace=checkpoint_native_trace;
    puts("PASS pending signal wake: native execution, entry/return packets, borrowed owner and duplicate suppression");
}
static void notification_signal_entry(void) {
    for (unsigned reset=0;reset<2;++reset) {
        struct thread t={.pid=123,.phase=SIGNAL_ENTRY,.kernel_stopped=1,
            .signals.actions=md_signal_actions_new()};
        assert(t.signals.actions);
        struct signal_control *owner=calloc(1,sizeof(*owner)); assert(owner);
        struct md_signal_entry packet={.pending=1};
        t.signals.control=owner; owner->signal=SIGALRM; owner->started=1;
        owner->entry_packet=&packet; owner->entry_thread=&t;
        owner->entry_previous=&signal_entries; signal_entries=owner;
        struct md_kernel_action action={.handler=0x1234,.flags=reset ? SA_RESETHAND : 0};
        assert(!md_signal_actions_commit(t.signals.actions,SIGALRM,&action));
        signal_entries_progress();
        assert(t.signals.control==owner && t.phase==SIGNAL_ENTRY);
        /* The handler acknowledges entry after the global scan but before its
         * syscall is admitted. No ptrace stop is needed to retire the packet. */
        atomic_store_explicit(&packet.pending,0,memory_order_release);
        owner->returning=1; assert(!signal_entry_committed(&t)); owner->returning=0;
        assert(signal_entry_committed(&t) && !t.signals.control && t.phase==IDLE && !signal_entries);
        assert(!signal_entry_committed(&t));
        assert(!md_signal_actions_read(t.signals.actions,SIGALRM,&action));
        assert(action.handler==(reset ? (uintptr_t)SIG_DFL : 0x1234));
        md_signal_actions_release(t.signals.actions);
    }
    puts("PASS signal-entry acknowledgement at notification admission after the earlier global scan");
}
static void fd_native_retirement(void) {
    struct md_filesystem fs={0};
    struct md_fs_anchor *origin=NULL;
    struct md_fs_output output={.origin=&origin};
    struct md_fs_result result={.fd=memfd_create("fd-retirement",MFD_CLOEXEC)};
    assert(result.fd>=0);
    md_view_capture(&fs,NULL,NULL,&result,&output);
    assert(!result.error && origin); close(result.fd);
    for (unsigned operation=0;operation<2;++operation) for (unsigned mode=0;mode<6;++mode) {
        struct thread t={.pid=123,.phase=IDLE};
        struct md_fd_origins *files=md_fd_origins_new(),*peer=NULL; assert(files);
        t.resources.files=files;
        int tracked=operation || (mode>=2 && mode!=5);
        if (tracked) {
            struct md_fd_origin_update update;
            assert(!md_fd_origins_prepare(files,origin,FD_CLOEXEC,&update));
            md_fd_origins_finish(&update,5);
        }
        if (mode==1) peer=md_fd_origins_fork(files,1);
        if (mode==3) assert(!md_fd_origins_abandon_range(files,5,5));
        if (mode==4 && operation) t.debug.options=PTRACE_O_TRACEEXEC;
        if (mode==5) t.seccomp.application_filter=1;
        /* Abandoning an ordinary origin removes it; it is not an uncertain
         * infrastructure slot. Close remains metadata-free, flags do not. */
        int direct=mode==0 || mode==5 || (!operation && mode==3);
        fd_test_operation=operation; fd_test_mode=mode;
        fd_expected_resume=direct ? PTRACE_CONT : PTRACE_SYSCALL;
        fd_resume_calls=0; checkpoints.trace=fd_resume_trace;
        thread_index[t.pid%THREAD_BUCKETS]=&t;
        if (operation) {
            t.descriptors.call=(struct seccomp_data){.nr=SYS_fcntl,
                .args={5,(UINT64_C(1)<<32)|F_SETFD,mode==2 ? 0 : FD_CLOEXEC}};
            memset(&t.descriptors.registers,0xa5,sizeof(t.descriptors.registers));
            assert(md_fd_order_begin(files,&t.descriptors.order,NULL)==1 && !md_fd_order_start(&t.descriptors.order));
            fd_native_start(&t);
        } else {
            struct ptrace_syscall_info info={.op=PTRACE_SYSCALL_INFO_SECCOMP,.arch=AUDIT_ARCH_AARCH64,
                .seccomp={.nr=SYS_close,.args={5}}};
            memset(&t.descriptors.registers,0xa5,sizeof(t.descriptors.registers));
            struct user_pt_regs untouched=t.descriptors.registers;
            assert(fd_close_compact(&t,MD_INTERCEPT_FD,&info));
            assert(!memcmp(&untouched,&t.descriptors.registers,sizeof(untouched)));
        }
        assert(fd_resume_calls==1 && !t.resume_lost && t.descriptors.running==!direct);
        assert(!!t.descriptors.order.table==!direct);
        if (!direct) fd_finish(&t,-EPERM);
        checkpoints.trace=checkpoint_native_trace;
        thread_index[t.pid%THREAD_BUCKETS]=NULL;
        md_fd_origins_release(peer); md_fd_origins_release(files);
    }
    md_fs_anchor_release(origin);
    puts("PASS native FD retirement: real syscall continuation, metadata-free close, shared/changed/debugged observation, filtered no-op and wide command");
}
static long duplicate_exit_result;
static unsigned duplicate_exit_reads;
static long duplicate_exit_trace(int request,pid_t pid,void *address,void *data) {
    if (request==PTRACE_GETREGSET) {
        struct iovec *io=data;
        assert(pid==123 && (uintptr_t)address==NT_PRSTATUS && io->iov_len==sizeof(long));
        *(long *)io->iov_base=duplicate_exit_result; ++duplicate_exit_reads; return 0;
    }
    return fd_resume_trace(request,pid,address,data);
}
static void duplicate_prepared_admission(void) {
    struct thread t={.pid=123,.phase=IDLE,.ready=1};
    t.resources.files=md_fd_origins_new(); assert(t.resources.files);
    t.descriptors.selector=md_fd_selector_new(); assert(t.descriptors.selector);
    struct user_pt_regs regs={0}; regs.regs[8]=SYS_fcntl;
    regs.regs[0]=5; regs.regs[1]=F_DUPFD_CLOEXEC;
    struct seccomp_data call=syscall_description(&regs);
    assert(!fd_duplicate_prepared(&t,&call,MD_INTERCEPT_DISPATCH));
    for (unsigned kind=0;kind<MD_FD_SELECTOR_KINDS;++kind)
        if (MD_FD_SANDBOX_SELECTORS&(1U<<kind)) assert(!md_fd_selector_mark(t.descriptors.selector,kind,-1));
    struct md_fd_object *object=md_descriptor_inode_new(&t.identity,S_IFIFO|0600); assert(object);
    struct md_fd_origin_update update;
    assert(!md_fd_origins_prepare_object(t.resources.files,object,0,&update));
    md_fd_origins_finish(&update,5);
    struct md_fd_origins *peer=md_fd_origins_fork(t.resources.files,1); assert(peer);
    for (unsigned shared=0;shared<2;++shared)
    for (unsigned operation=0;operation<4;++operation) for (unsigned success=0;success<2;++success) {
        struct md_entry_channel channel={.call={.reserved=MD_ENTRY_EXECUTION_BEGIN}};
        t.entry.control=shared ? &channel : NULL; t.entry.admission=shared;
        regs.regs[8]=operation<2 ? SYS_fcntl : operation==2 ? SYS_dup : SYS_dup3;
        regs.regs[1]=operation<2 ? (UINT64_C(1)<<32)|(operation ? F_DUPFD : F_DUPFD_CLOEXEC) : 6;
        regs.regs[2]=operation==3 ? O_CLOEXEC : 0;
        call=syscall_description(&regs);
        assert(fd_duplicate_prepared(&t,&call,MD_INTERCEPT_DISPATCH));
        assert(!fd_duplicate_prepared(&t,&call,MD_INTERCEPT_PROC));
        t.debug.options=PTRACE_O_TRACEEXEC;
        assert(!fd_duplicate_prepared(&t,&call,MD_INTERCEPT_DISPATCH)); t.debug.options=0;
        t.seccomp.application_filter=1;
        assert(fd_duplicate_prepared(&t,&call,MD_INTERCEPT_DISPATCH));
        fd_expected_resume=PTRACE_SYSCALL; fd_resume_calls=0;
        checkpoints.trace=shared ? no_trace : fd_resume_trace; thread_index[t.pid%THREAD_BUCKETS]=&t;
        unsigned long cookie=MD_INTERCEPT_DISPATCH;
        assert(resource_call(&t,&call,shared ? NULL : &regs,&cookie));
        assert(fd_resume_calls==!shared && t.descriptors.running && t.descriptors.order.table);
        if (shared) {
            assert(t.entry.effect==fd_entry_complete && !t.entry.execution);
            assert(channel.native.number==call.nr && !memcmp(channel.native.args,call.args,sizeof(call.args)));
        }
        struct md_fd_object *before=md_fd_origins_object(peer,6,NULL); assert(!before);
        if (shared) fd_finish(&t,success ? 6 : -EPERM);
        else {
            checkpoints.trace=duplicate_exit_trace;
            duplicate_exit_result=success ? 6 : -EPERM; duplicate_exit_reads=0;
            fd_expected_resume=PTRACE_CONT;
            assert(descriptors_native_exit(&t,t.pid));
            assert(duplicate_exit_reads==1 && fd_resume_calls==2);
        }
        t.entry.effect=NULL; t.entry.control=NULL; t.entry.admission=0;
        assert(!t.descriptors.running && !t.descriptors.order.table);
        unsigned flags=0;
        struct md_fd_object *after=md_fd_origins_object(peer,6,&flags);
        assert(after==(success ? object : NULL));
        if (success) assert(flags==((operation==0 || operation==3) ? FD_CLOEXEC : 0));
        md_fd_object_release(after);
        assert(!md_fd_origins_close_range(peer,6,6,0));
        checkpoints.trace=checkpoint_native_trace; thread_index[t.pid%THREAD_BUCKETS]=NULL;
    }
    regs.regs[8]=SYS_fcntl; regs.regs[1]=F_SETFL;
    call=syscall_description(&regs);
    assert(!fd_duplicate_prepared(&t,&call,MD_INTERCEPT_DISPATCH));
    md_fd_object_release(object); md_fd_origins_release(peer); md_fd_origins_release(t.resources.files);
    md_fd_selector_release(t.descriptors.selector);
    puts("PASS prepared duplicate: common Base/channel admission, shared authority, kernel failure, CLOEXEC and debugger fallback");
}
static void fd_shared_mutations(void) {
    const struct seccomp_data calls[]={
        {.nr=SYS_close,.args={5}}, {.nr=SYS_dup,.args={5}},
        {.nr=SYS_dup3,.args={5,6,O_CLOEXEC}},
        {.nr=SYS_fcntl,.args={5,F_DUPFD_CLOEXEC,6}},
        {.nr=SYS_fcntl,.args={5,F_SETFD,FD_CLOEXEC}},
        {.nr=SYS_close_range,.args={5,6,0}},
        {.nr=SYS_epoll_create1,.args={EPOLL_CLOEXEC}}};
    for (unsigned i=0;i<sizeof(calls)/sizeof(*calls);++i) {
        struct md_entry_channel channel={.call={.reserved=MD_ENTRY_EXECUTION_BEGIN}};
        struct thread t={.pid=123,.entry={.control=&channel,.admission=1}};
        t.resources.files=md_fd_origins_new(); assert(t.resources.files);
        struct md_fd_object *object=md_descriptor_inode_new(&t.identity,S_IFIFO|0600); assert(object);
        struct md_fd_origin_update update;
        assert(!md_fd_origins_prepare_object(t.resources.files,object,0,&update));
        md_fd_origins_finish(&update,5); md_fd_object_release(object);
        memset(&t.descriptors.registers,0xa5,sizeof(t.descriptors.registers));
        struct user_pt_regs untouched=t.descriptors.registers;
        fd_begin(&t,&calls[i],NULL,MD_INTERCEPT_FD);
        assert(t.descriptors.running && t.entry.effect==fd_entry_complete);
        assert(atomic_load(&channel.state)==MD_ENTRY_NATIVE && channel.native.number==calls[i].nr);
        assert(!memcmp(channel.native.args,calls[i].args,sizeof(calls[i].args)));
        assert(!memcmp(&untouched,&t.descriptors.registers,sizeof(untouched)));
        assert(!descriptors_native_exit(&t,t.pid));
        channel.call.reserved=MD_ENTRY_EFFECT_DONE;
        assert(entry_pending(&t));
        fd_finish(&t,-EBADF);
        assert(!t.descriptors.running && !t.descriptors.order.table && !t.close.closed.first);
        md_fd_origins_release(t.resources.files);
    }
    puts("PASS shared FD mutations: typed native effects, stable admission ownership and no machine context");
}
static void pidfd_shared_birth(void) {
    for (unsigned translated=0;translated<2;++translated) for (unsigned failed=0;failed<2;++failed) {
        struct md_entry_channel channel={.call={.reserved=MD_ENTRY_EXECUTION_BEGIN}};
        struct thread t={.pid=123,.tgid=123,.entry={.control=&channel,.admission=1}};
        t.resources.files=md_fd_origins_new(); assert(t.resources.files);
        struct md_pid_namespace *space=md_pid_namespace_new(NULL,NULL); assert(space);
        struct md_pid_identity *identity=NULL;
        assert(!md_pid_identity_prepare(space,&identity) && !md_pid_identity_publish(identity,t.pid,t.tgid));
        if (translated) t.resources.pid=t.resources.pid_group=identity;
        thread_index[t.pid%THREAD_BUCKETS]=&t;
        const struct seccomp_data call={.nr=SYS_pidfd_open,.args={translated ? 1 : 123,0}};
        unsigned long cookie=MD_INTERCEPT_PROCESS;
        memset(&t.descriptors.registers,0xa5,sizeof(t.descriptors.registers));
        struct user_pt_regs untouched=t.descriptors.registers;
        assert(process_call(&t,&call,NULL,&cookie));
        assert(t.descriptors.running && t.entry.effect==fd_entry_complete);
        assert(atomic_load(&channel.state)==MD_ENTRY_NATIVE && channel.native.number==SYS_pidfd_open);
        assert(channel.native.args[0]==123 && !channel.native.args[1]);
        assert(!memcmp(&untouched,&t.descriptors.registers,sizeof(untouched)));
        fd_finish(&t,failed ? -EINVAL : 7);
        assert(!t.descriptors.running && !t.descriptors.order.table);
        struct md_fd_object *object=md_fd_origins_object(t.resources.files,7,NULL);
        assert(md_pid_descriptor_identity(object)==(translated && !failed ? identity : NULL));
        md_fd_object_release(object); md_fd_origins_release(t.resources.files);
        thread_index[t.pid%THREAD_BUCKETS]=NULL;
        md_pid_identity_release(identity); md_pid_namespace_release(space);
    }
    puts("PASS shared pidfd birth: translated identity, native effect and exact success/failure publication without registers");
}
static void process_shared_waits(void) {
    struct md_debugger *saved=debugger;
    debugger=md_debugger_create(&(struct md_debugger_host){0}); assert(debugger);
    const struct seccomp_data calls[]={
        {.nr=SYS_wait4,.instruction_pointer=0x1000,.args={123,0x2000,WNOHANG,0x3000}},
        {.nr=SYS_waitid,.instruction_pointer=0x1000,.args={P_PID,123,0x2000,WEXITED|WNOWAIT,0x3000}}};
    for (unsigned translated=0;translated<2;++translated) for (unsigned i=0;i<sizeof(calls)/sizeof(*calls);++i) {
        struct md_entry_channel channel={.call={.reserved=MD_ENTRY_EXECUTION_BEGIN}};
        struct thread t={.pid=123,.tgid=123,.entry={.control=&channel,.admission=1}};
        struct md_pid_namespace *space=md_pid_namespace_new(NULL,NULL); assert(space);
        struct md_pid_identity *identity=NULL;
        assert(!md_pid_identity_prepare(space,&identity) && !md_pid_identity_publish(identity,t.pid,t.tgid));
        if (translated) t.resources.pid=t.resources.pid_group=identity;
        struct seccomp_data call=calls[i];
        call.args[i ? 1 : 0]=translated ? 1 : 123;
        thread_index[t.pid%THREAD_BUCKETS]=&t;
        unsigned long cookie=translated ? MD_INTERCEPT_PROCESS : MD_INTERCEPT_DISPATCH;
        memset(&t.execution.registers,0xa5,sizeof(t.execution.registers));
        struct user_pt_regs untouched=t.execution.registers;
        assert(process_call(&t,&call,NULL,&cookie));
        assert(t.entry.effect==process_entry_complete && t.entry.waiting);
        assert(atomic_load(&channel.state)==MD_ENTRY_NATIVE && channel.native.number==call.nr);
        assert(!memcmp(channel.native.args,calls[i].args,sizeof(call.args)));
        assert(!memcmp(&t.execution.request,&call,sizeof(call)));
        assert(!memcmp(&untouched,&t.execution.registers,sizeof(untouched)));
        assert(!process_wait_output(&t,-ECHILD));
        assert(process_native_result(&t,-ECHILD)==-ECHILD && !t.process_op.wait_result);
        assert(!memcmp(&untouched,&t.execution.registers,sizeof(untouched)));
        assert(process_wait_interrupt(&t) && t.process_op.wait_recheck);
        assert(atomic_load(&channel.native.state)==MD_NATIVE_CANCELLED);
        t.entry.effect=NULL; t.entry.waiting=0;
        process_entry_complete(&t,-EINTR);
        assert(!t.process_op.wait_recheck && t.entry.effect==process_entry_complete);
        assert(atomic_load(&channel.native.state)==MD_NATIVE_READY);
        assert(!memcmp(channel.native.args,calls[i].args,sizeof(call.args)));
        channel.native.result=i ? 0 : 123;
        atomic_store(&channel.native.state,MD_NATIVE_DONE);
        assert(process_wait_interrupt(&t) && !t.process_op.wait_recheck);
        assert(atomic_load(&channel.native.state)==MD_NATIVE_DONE && channel.native.result==(i ? 0 : 123));
        assert(!memcmp(&untouched,&t.execution.registers,sizeof(untouched)));
        thread_index[t.pid%THREAD_BUCKETS]=NULL;
        md_pid_identity_release(identity); md_pid_namespace_release(space);
    }
    md_debugger_destroy(debugger); debugger=saved;
    puts("PASS shared native waits: baseline/PID translation, cancelled-only retry, exact receipt and untouched register state");
}
static pid_t debug_reaped_child;
static struct md_entry_channel *debug_stop_reply;
static enum md_entry_outcome debug_reply_outcome=MD_ENTRY_NEEDS_STOP;
static long debug_reply_value=RESTART_SIGNAL;
void __real_md_broker_entry_reply(struct md_namespace_broker *,struct md_entry_channel *,enum md_entry_outcome,long);
void __wrap_md_broker_entry_reply(struct md_namespace_broker *owner,struct md_entry_channel *channel,
        enum md_entry_outcome outcome,long result) {
    if (!debug_stop_reply) { __real_md_broker_entry_reply(owner,channel,outcome,result); return; }
    if (channel!=debug_stop_reply || outcome!=debug_reply_outcome || result!=debug_reply_value)
        fprintf(stderr,"entry reply: channel=%p expected=%p outcome=%d expected=%d result=%ld expected=%ld\n",
            (void *)channel,(void *)debug_stop_reply,outcome,debug_reply_outcome,result,debug_reply_value);
    assert(channel==debug_stop_reply && outcome==debug_reply_outcome && result==debug_reply_value);
    debug_stop_reply=NULL;
}
static void debug_wait_reaped(pid_t observer,pid_t child,int native_wait) {
    assert(observer==getpid() && native_wait); debug_reaped_child=child;
}
static void process_wait_signal_receipts(void) {
    const long calls[]={SYS_wait4,SYS_waitid};
    for (unsigned i=0;i<sizeof(calls)/sizeof(*calls);++i) for (unsigned cancel=0;cancel<2;++cancel) {
        struct md_entry_channel channel={.call={.reserved=MD_ENTRY_EFFECT_DONE}};
        struct thread t={.pid=123,.tgid=123,.entry={.control=&channel,.admission=1,
            .effect=process_entry_complete,.waiting=1}};
        t.execution.request.nr=calls[i];
        memset(&t.execution.registers,0xa5,sizeof(t.execution.registers));
        struct user_pt_regs untouched=t.execution.registers;
        channel.native.result=-EINTR;
        atomic_store(&channel.native.state,cancel ? MD_NATIVE_CANCELLED : MD_NATIVE_DONE);
        debug_stop_reply=&channel;
        debug_reply_outcome=cancel ? MD_ENTRY_NEEDS_STOP : MD_ENTRY_COMPLETED;
        debug_reply_value=cancel ? RESTART_SIGNAL : -EINTR;
        thread_index[t.pid%THREAD_BUCKETS]=&t;
        checkpoints.trace=no_trace;
        entry_control(t.pid,&channel);
        assert(!t.entry.effect && !t.entry.control && !t.entry.waiting);
        if (cancel) {
            assert(!debug_stop_reply && t.entry.stop.resume==entry_restart_stopped);
            assert(t.entry.stop.reason==MD_CONTEXT_SIGNAL && t.execution.restart==RESTART_SIGNAL);
        } else {
            assert(!debug_stop_reply && !t.entry.stop.resume && channel.execution_action==MD_EXECUTION_RESULT);
        }
        assert(!memcmp(&untouched,&t.execution.registers,sizeof(untouched)));
        thread_index[t.pid%THREAD_BUCKETS]=NULL;
    }
    checkpoints.trace=checkpoint_native_trace;
    debug_reply_outcome=MD_ENTRY_NEEDS_STOP; debug_reply_value=RESTART_SIGNAL;
    puts("PASS process wait receipts: cancelled signal boundary, completed EINTR and no register access");
}
static void memory_shared_detach(void) {
    for (unsigned queued=0;queued<2;++queued) {
        struct md_entry_channel channel={0};
        struct thread t={.pid=123,.tgid=123,.ready=1,.memory=md_memory_new(),
            .entry={.control=&channel,.admission=1}};
        assert(t.memory);
        struct seccomp_data call={.nr=SYS_shmdt,.instruction_pointer=0x1004,.args={0x2000}};
        struct md_resource_order before={0};
        if (queued) assert(md_memory_order_begin(t.memory,&before,NULL)==1 && !md_resource_order_start(&before));
        memset(&t.execution.registers,0xa5,sizeof(t.execution.registers));
        struct user_pt_regs untouched=t.execution.registers;
        unsigned long cookie=MD_INTERCEPT_DISPATCH;
        debug_stop_reply=&channel; debug_reply_outcome=MD_ENTRY_COMPLETED; debug_reply_value=0;
        checkpoints.trace=no_trace;
        assert(resource_call(&t,&call,NULL,&cookie));
        if (queued) {
            assert(debug_stop_reply==&channel && t.phase==MEMORY_WAIT && !t.entry.execution);
            assert(md_resource_order_state(&t.memory_op.order)==MD_RESOURCE_ORDER_QUEUED);
            assert(!md_resource_order_complete(&before)); memory_start(&t);
        }
        assert(!debug_stop_reply && t.phase==DISPATCHING && t.entry.execution==&channel);
        assert(channel.execution_action==MD_EXECUTION_GUEST && channel.execution.number==SYS_shmdt);
        assert(!memcmp(channel.execution.args,call.args,sizeof(call.args)));
        assert(t.memory_op.action==MEMORY_DETACH
            && md_resource_order_state(&t.memory_op.order)==MD_RESOURCE_ORDER_RUNNING);
        assert(execution_complete(&t,queued ? -EINVAL : 0)==EXECUTION_RETURN);
        assert(!t.memory_op.action && !t.memory_op.order.queue);
        assert(!memcmp(&untouched,&t.execution.registers,sizeof(untouched)));
        assert(!t.retries[0].mask && !t.retries[1].mask);
        md_memory_release(t.memory);
    }
    checkpoints.trace=checkpoint_native_trace;
    debug_reply_outcome=MD_ENTRY_NEEDS_STOP; debug_reply_value=RESTART_SIGNAL;
    puts("PASS shared SysV detach: common memory turn, queued admission, exact retirement and no register access");
}
static void readiness_local_duplicate(void) {
    int previous_fd_ready=fd_ready;
    fd_ready=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK); assert(fd_ready>=0);
    checkpoints.trace=no_trace;
    const long results[]={14,-EMFILE,-EBADF,-EINTR};
    for (unsigned abandon=0;abandon<2;++abandon)
    for (unsigned i=0;i<sizeof(results)/sizeof(*results);++i) {
        struct md_fd_origins *files=md_fd_origins_new(); assert(files);
        struct md_entry_channel channel={0};
        struct readiness_owner *o=calloc(1,sizeof(*o)); assert(o);
        for (unsigned n=0;n<READINESS_QUEUES;++n) o->loans[n].number=-1;
        struct thread t={.pid=123,.tgid=123,.readiness=o,.resources.files=files,
            .entry={.control=&channel,.execution=&channel}};
        t.execution.request=(struct seccomp_data){.nr=SYS_epoll_pwait,.args={7,0,1,-1}};
        thread_index[t.pid%THREAD_BUCKETS]=&t;
        assert(md_fd_order_begin(files,&o->files,NULL)==1 && !md_fd_order_start(&o->files));
        readiness_duplicate(&t);
        assert(t.entry.effect==readiness_duplicate_done && !t.entry.waiting);
        assert(channel.native.number==SYS_fcntl && channel.native.args[0]==7
            && channel.native.args[1]==F_DUPFD_CLOEXEC && !channel.native.args[2]);
        assert(o->loans[0].publication.table==files && o->loans[0].number==-1
            && !o->capture.channel && !o->received && !o->loans[0].delivery.files.table);
        channel.native.result=results[i];
        atomic_store(&channel.native.state,i==3 ? MD_NATIVE_READY : MD_NATIVE_DONE);
        if (!abandon) {
            if (i==3) atomic_store(&channel.native.state,MD_NATIVE_CANCELLED);
            channel.call.reserved=MD_ENTRY_EFFECT_DONE;
            debug_stop_reply=&channel; debug_reply_outcome=MD_ENTRY_COMPLETED;
            debug_reply_value=i==3 ? RESTART_SIGNAL : results[i]<0 ? results[i] : MD_EPOLL_CAPTURED;
            entry_control(t.pid,&channel);
            assert(!debug_stop_reply && !t.entry.effect && !t.entry.control);
            assert(!o->files.table && !o->loans[0].publication.table);
            assert(o->loans[0].number==(i ? -1 : 14) && o->native_caller==!i);
        }
        readiness_release(&t);
        assert(!t.readiness && !t.entry.effect);
        struct md_fd_order turn={0};
        struct md_fd_order *retiring=i ? &turn : &loan_retirements->files;
        if (i) assert(md_fd_order_begin(files,retiring,NULL)==1);
        assert(!md_fd_order_start(retiring));
        int number=-1;
        assert(md_fd_origins_loan_next(retiring,&number)==!i);
        if (!i) {
            assert(number==14 && loan_retirements && task_action_calls==1);
            assert(!md_fd_origins_close_range(files,14,14,0));
            task_action_release(loan_retirements);
        }
        assert(!loan_retirements && !task_action_calls);
        if (i) assert(!md_fd_order_complete(&turn));
        md_fd_origins_release(files); thread_index[t.pid%THREAD_BUCKETS]=NULL;
    }
    checkpoints.trace=checkpoint_native_trace;
    debug_reply_outcome=MD_ENTRY_NEEDS_STOP; debug_reply_value=RESTART_SIGNAL;
    close(fd_ready); fd_ready=previous_fd_ready;
    puts("PASS readiness local duplicate: exact loan publication, allocation errors, cancelled plans and terminal cleanup without capture or ADDFD");
}

static void adapter_native_receipts(void) {
    struct md_entry_channel local={0};
    struct thread owner={.phase=DISPATCHING,.entry.execution=&local};
    assert(!entry_native_journal(&owner) && !entry_fence_drained(&owner));
    const unsigned states[]={MD_NATIVE_READY,MD_NATIVE_DONE,MD_NATIVE_CANCELLED};
    for (unsigned i=0;i<sizeof(states)/sizeof(*states);++i) {
        local.adapter_native.number=SYS_recvmsg;
        local.adapter_native.result=states[i]==MD_NATIVE_READY ? 0 : -EINTR;
        atomic_store(&local.adapter_native.state,states[i]);
        assert(entry_native_journal(&owner)==&local.adapter_native && entry_fence_drained(&owner));
        assert(!entry_effect_observe(&owner));
        assert(atomic_load(&local.adapter_native.state)==(states[i]==MD_NATIVE_DONE ? MD_NATIVE_DONE : MD_NATIVE_CANCELLED));
        assert(local.adapter_native.result==-EINTR && !owner.entry.control && !owner.entry.effect);
        owner.descriptors.running=1;
        assert(!entry_fence_drained(&owner)); owner.descriptors.running=0;
    }
    atomic_store(&local.adapter_native.state,MD_NATIVE_EMPTY);
    assert(!entry_native_journal(&owner) && !entry_effect_observe(&owner) && !entry_fence_drained(&owner));
    /* Native-result ownership is published on every receipt, not inferred
     * from the guest syscall number or retained from the preceding request. */
    for (unsigned managed=0;managed<2;++managed) {
        owner.descriptors.job=managed ? MD_FD_DUPLICATE_BEGIN : 0;
        owner.entry.control=&local;
        local.adapter_native_managed=!managed;
        debug_stop_reply=&local; debug_reply_outcome=MD_ENTRY_COMPLETED; debug_reply_value=0;
        assert(entry_reply(&owner,0,0) && !debug_stop_reply);
        assert(local.adapter_native_managed==managed && !owner.entry.control);
    }
    owner.descriptors.job=0;
    const long native_calls[]={SYS_gettid,SYS_recvmsg};
    for (unsigned retained=0;retained<2;++retained)
    for (unsigned i=0;i<sizeof(native_calls)/sizeof(*native_calls);++i) {
        local.call=(struct md_entry_request){.number=native_calls[i]};
        owner=(struct thread){.pid=123,.entry={.control=&local,.admission=1}};
        thread_index[owner.pid%THREAD_BUCKETS]=&owner;
        debug_stop_reply=&local; debug_reply_outcome=MD_ENTRY_COMPLETED; debug_reply_value=0;
        if (retained) {
            owner.phase=DISPATCHING;
            owner.entry.control=NULL; owner.entry.admission=0; owner.entry.execution=&local;
            owner.execution.request.nr=native_calls[i];
            entry_execution_finish(&owner,MD_CALL_NATIVE);
        } else native_continue(&owner);
        assert(!debug_stop_reply && !owner.entry.control && !owner.entry.effect);
        assert(owner.phase==IDLE && !owner.entry.execution);
        assert(local.execution_action==MD_EXECUTION_NATIVE && !local.adapter_native_managed);
        thread_index[owner.pid%THREAD_BUCKETS]=NULL;
    }
    for (unsigned retained=0;retained<2;++retained) {
        local.call=(struct md_entry_request){.number=SYS_gettid};
        owner=(struct thread){.pid=123,.entry={.control=&local,.admission=1},
            .seccomp.application_filter=1};
        thread_index[owner.pid%THREAD_BUCKETS]=&owner;
        if (retained) {
            owner.phase=DISPATCHING;
            owner.entry.control=NULL; owner.entry.admission=0; owner.entry.execution=&local;
            owner.execution.request.nr=SYS_gettid;
            entry_execution_finish(&owner,MD_CALL_NATIVE);
        } else native_continue(&owner);
        assert(owner.entry.control==&local && owner.entry.effect && !owner.entry.waiting);
        assert(atomic_load(&local.native.state)==MD_NATIVE_READY && local.native.number==SYS_gettid);
        thread_index[owner.pid%THREAD_BUCKETS]=NULL;
    }
    const long results[]={0,-EACCES,-ENOTTY,-EINTR};
    const long calls[]={SYS_ioctl,SYS_connect,SYS_recvmsg,SYS_readv};
    for (unsigned call=0;call<sizeof(calls)/sizeof(*calls);++call)
    for (unsigned i=0;i<sizeof(results)/sizeof(*results);++i) for (unsigned cancel=0;cancel<2;++cancel) {
        if (cancel && results[i]!=-EINTR) continue;
        struct md_entry_channel channel={.call={.number=SYS_ioctl,.reserved=MD_ENTRY_NATIVE_REQUEST,
            .args={7,TCSETSW2,0x12340}}};
        channel.call.number=calls[call];
        if (call) { channel.call.args[1]=0x12340; channel.call.args[2]=0; }
        struct thread t={.pid=123,.phase=DISPATCHING,.entry={.execution=&channel}};
        memset(&t.execution.registers,0xa5,sizeof(t.execution.registers));
        struct user_pt_regs untouched=t.execution.registers;
        thread_index[t.pid%THREAD_BUCKETS]=&t; checkpoints.trace=no_trace;
        entry_control(t.pid,&channel);
        assert(t.entry.effect==entry_adapter_native_done && t.entry.waiting);
        assert(channel.native.number==calls[call] && !memcmp(channel.native.args,channel.call.args,sizeof(channel.call.args)));
        assert(atomic_load(&channel.native.state)==MD_NATIVE_READY);
        channel.call.reserved=MD_ENTRY_EFFECT_DONE;
        channel.native.result=results[i];
        atomic_store(&channel.native.state,cancel ? MD_NATIVE_CANCELLED : MD_NATIVE_DONE);
        debug_stop_reply=&channel; debug_reply_outcome=MD_ENTRY_COMPLETED;
        debug_reply_value=cancel ? RESTART_SIGNAL : results[i];
        entry_control(t.pid,&channel);
        assert(!debug_stop_reply && !t.entry.effect && !t.entry.control && !t.entry.waiting);
        assert(t.entry.execution==&channel && t.phase==DISPATCHING && !t.entry.stop.resume);
        assert(!memcmp(&untouched,&t.execution.registers,sizeof(untouched)));
        thread_index[t.pid%THREAD_BUCKETS]=NULL;
    }
    checkpoints.trace=checkpoint_native_trace;
    debug_reply_outcome=MD_ENTRY_NEEDS_STOP; debug_reply_value=RESTART_SIGNAL;
    puts("PASS C native effects: typed operands, exact receipts, cancelled-only restart and retained C owner without registers");
}

static void duplicate_entry_receipts(void) {
    const long numbers[]={SYS_dup,SYS_dup3,SYS_fcntl};
    for (unsigned i=0;i<sizeof(numbers)/sizeof(*numbers);++i) for (unsigned cancel=0;cancel<2;++cancel) {
        struct md_fd_origins *files=md_fd_origins_new(); assert(files);
        struct md_entry_channel channel={0};
        struct thread t={.pid=123,.phase=DISPATCHING,.resources.files=files,.entry.execution=&channel};
        t.execution.request=(struct seccomp_data){.nr=(int)numbers[i],.arch=AUDIT_ARCH_AARCH64,
            .args={5,i==1 ? 9 : i==2 ? F_DUPFD_CLOEXEC : 0,i==1 ? O_CLOEXEC : 0}};
        memset(&t.execution.registers,0xa5,sizeof(t.execution.registers));
        memset(&t.descriptors.registers,0x5a,sizeof(t.descriptors.registers));
        struct user_pt_regs original=t.execution.registers,internal=t.descriptors.registers;
        struct md_fd_observation view; assert(!md_fd_origins_observe(files,5,&view));
        channel.call=(struct md_entry_request){.number=MD_FD_DUPLICATE_BEGIN,
            .reserved=MD_ENTRY_FD_CONTROL,.args={view.generation}};
        thread_index[t.pid%THREAD_BUCKETS]=&t; checkpoints.trace=no_trace;
        debug_stop_reply=&channel; debug_reply_outcome=MD_ENTRY_COMPLETED; debug_reply_value=view.selectors;
        entry_control(t.pid,&channel);
        assert(!debug_stop_reply && t.descriptors.job==MD_FD_DUPLICATE_BEGIN && !t.entry.control);
        assert(channel.adapter_native_managed);
        assert(md_fd_order_state(&t.descriptors.order)==MD_RESOURCE_ORDER_RUNNING);
        channel.call.number=numbers[i]; channel.call.reserved=MD_ENTRY_NATIVE_REQUEST;
        memcpy(channel.call.args,t.execution.request.args,sizeof(channel.call.args));
        entry_control(t.pid,&channel);
        assert(t.descriptors.running && t.entry.effect==fd_entry_complete);
        assert(channel.native.number==numbers[i] && !memcmp(channel.native.args,channel.call.args,sizeof(channel.call.args)));
        channel.call.reserved=MD_ENTRY_EFFECT_DONE;
        channel.native.result=cancel ? -EINTR : 9;
        atomic_store(&channel.native.state,cancel ? MD_NATIVE_CANCELLED : MD_NATIVE_DONE);
        debug_stop_reply=&channel; debug_reply_value=cancel ? RESTART_SIGNAL : 9;
        entry_control(t.pid,&channel);
        assert(!debug_stop_reply && !t.descriptors.running && !t.entry.control && !t.entry.effect);
        assert(t.descriptors.duplicate_result==(cancel ? -1 : 9));
        assert(md_fd_order_state(&t.descriptors.order)==MD_RESOURCE_ORDER_RUNNING);
        channel.call=(struct md_entry_request){.number=MD_FD_DUPLICATE_END,.reserved=MD_ENTRY_FD_CONTROL};
        debug_stop_reply=&channel; debug_reply_value=0;
        entry_control(t.pid,&channel);
        assert(!debug_stop_reply && !t.descriptors.job && !t.descriptors.order.table);
        assert(!channel.adapter_native_managed);
        assert(!memcmp(&original,&t.execution.registers,sizeof(original)));
        assert(!memcmp(&internal,&t.descriptors.registers,sizeof(internal)));
        assert(t.phase==DISPATCHING && t.entry.execution==&channel);
        thread_index[t.pid%THREAD_BUCKETS]=NULL; md_fd_origins_release(files);
    }
    checkpoints.trace=checkpoint_native_trace;
    debug_reply_outcome=MD_ENTRY_NEEDS_STOP; debug_reply_value=RESTART_SIGNAL;
    puts("PASS shared duplicate: exact table turn, typed effect, cancellation and unchanged registers");
}
static long mapping_unexpected(void *context,const struct md_control_actor *actor,
        unsigned protection,unsigned flags,struct md_control_pending **pending) {
    (void)context; (void)actor; (void)protection; (void)flags; (void)pending;
    assert(!"provider called before C admission"); return -EIO;
}
static void mapping_shared_provider(void) {
    for (unsigned route=0;route<2;++route) for (unsigned queued=0;queued<2;++queued)
    for (unsigned valid=0;valid<2;++valid) {
        struct md_entry_channel channel={0};
        struct thread t={.pid=123,.tgid=123,.ready=1,.memory=md_memory_new(),
            .shm_space=md_shm_space_new(),.entry={.control=&channel,.admission=1}};
        t.resources.files=md_fd_origins_new();
        assert(t.memory && t.shm_space && t.resources.files);
        struct stat st={.st_mode=S_IFCHR|0666};
        struct md_control_file device=md_full_device_new(&st,NULL); assert(device.ops);
        struct md_control_file_ops ops=*device.ops; ops.mapping=mapping_unexpected;
        struct md_proc_file *file=md_proc_file_open_control(&ops,device.context,
            &(struct md_control_opener){.identity=&t.identity},O_RDONLY); assert(file);
        md_control_file_release(device);
        struct md_fd_origin_update update;
        assert(!md_fd_origins_prepare_object(t.resources.files,md_proc_file_object(file),0,&update));
        md_fd_origins_finish(&update,5);
        struct seccomp_data call={.nr=SYS_mmap,.args={0,valid ? 4096 : 0,PROT_READ,MAP_SHARED,5,0}};
        struct md_resource_order before={0};
        if (queued) assert(md_memory_order_begin(t.memory,&before,NULL)==1
            && !md_resource_order_start(&before));
        memset(&t.execution.registers,0xa5,sizeof(t.execution.registers));
        const struct user_pt_regs untouched=t.execution.registers;
        debug_stop_reply=&channel; debug_reply_outcome=MD_ENTRY_COMPLETED;
        debug_reply_value=valid ? 0 : -EINVAL; checkpoints.trace=no_trace;
        if (route) proc_lookup_begin(&t,&call,NULL);
        else memory_begin(&t,&call,NULL,MEMORY_KERNEL);
        if (queued) {
            assert(t.phase==MEMORY_WAIT && !t.entry.execution && debug_stop_reply==&channel);
            assert(!md_resource_order_complete(&before)); memory_start(&t);
        }
        assert(!debug_stop_reply && !t.entry.stop.resume && !t.entry.effect);
        assert(!t.memory_op.order.queue && !t.memory_op.files.table && !t.memory_op.action);
        assert(!memcmp(&untouched,&t.execution.registers,sizeof(untouched)));
        if (valid) {
            assert(t.phase==DISPATCHING && t.entry.execution==&channel && t.proc.file==file);
            assert(channel.execution_action==MD_EXECUTION_GUEST && channel.execution.number==SYS_mmap);
            assert(!memcmp(channel.execution.args,call.args,sizeof(call.args)));
        } else assert(t.phase==IDLE && !t.proc.file && channel.execution_action==MD_EXECUTION_RESULT);
        md_proc_file_release(t.proc.file); md_proc_file_release(file);
        md_fd_origins_release(t.resources.files);
        assert(!md_shm_space_release(t.shm_space)); md_memory_release(t.memory);
    }
    checkpoints.trace=checkpoint_native_trace;
    debug_reply_outcome=MD_ENTRY_NEEDS_STOP; debug_reply_value=RESTART_SIGNAL;
    puts("PASS provider mmap: memory/proc ingress, queued admission and validation without registers or held turns");
}
static void mapping_shared_admission(void) {
    for (unsigned valid=0;valid<2;++valid) for (unsigned queued=0;queued<2;++queued) {
        struct md_entry_channel channel={.call={.number=MD_BUFFER_MEMORY_MAP,
            .reserved=MD_ENTRY_BUFFER_CONTROL}};
        struct thread t={.pid=123,.tgid=123,.phase=DISPATCHING,.entry.execution=&channel,
            .memory=md_memory_new(),.shm_space=md_shm_space_new(),.control.mapping_fd=-1,
            .execution.request={.nr=SYS_mmap,.args={0,4096,PROT_READ,MAP_SHARED}}};
        assert(t.memory && t.shm_space && !md_memory_buffers_prepare(t.memory,1,4096,0,0));
        assert(!md_buffers_take(md_memory_buffers(t.memory),&t.fs.buffer));
        if (valid) {
            struct stat st={.st_mode=S_IFCHR|0666};
            struct md_control_file device=md_full_device_new(&st,NULL); assert(device.ops);
            t.proc.file=md_proc_file_open_control(device.ops,device.context,
                &(struct md_control_opener){.identity=&t.identity},O_RDWR);
            md_control_file_release(device);
            t.control.mapping_fd=memfd_create("mapping-admission",MFD_CLOEXEC);
            assert(t.proc.file && t.control.mapping_fd>=0 && !ftruncate(t.control.mapping_fd,4096));
        }
        struct md_resource_order before={0};
        if (queued) assert(md_memory_order_begin(t.memory,&before,NULL)==1
            && !md_resource_order_start(&before));
        memset(&t.execution.registers,0xa5,sizeof(t.execution.registers));
        memset(&t.fs.registers,0x5a,sizeof(t.fs.registers));
        struct user_pt_regs execution=t.execution.registers,control=t.fs.registers;
        thread_index[t.pid%THREAD_BUCKETS]=&t; checkpoints.trace=no_trace;
        debug_stop_reply=&channel;
        debug_reply_outcome=valid ? MD_ENTRY_NEEDS_STOP : MD_ENTRY_COMPLETED;
        debug_reply_value=valid ? 0 : -EBADF;
        entry_control(t.pid,&channel);
        if (queued && valid) {
            assert(debug_stop_reply==&channel && !t.entry.stop.resume && t.memory_op.mapping);
            assert(md_resource_order_state(&t.memory_op.mapping->order)==MD_RESOURCE_ORDER_QUEUED);
        }
        if (queued) assert(!md_resource_order_complete(&before));
        if (valid) {
            if (queued) mapping_start(&t);
            assert(!debug_stop_reply && t.memory_op.mapping && !t.memory_op.mapping->admitted);
            assert(t.entry.stop.resume==mapping_admitted && t.entry.stop.reason==MD_CONTEXT_MEMORY);
            assert(t.memory_op.mapping->request.size==4096 && t.control.mapping_fd==-1);
            memory_operation_discard(&t);
            assert(!t.memory_op.mapping);
            retry_clear(&t,RETRY_MAPPING);
        } else assert(!debug_stop_reply && !t.memory_op.mapping && !t.entry.stop.resume);
        assert(!memcmp(&execution,&t.execution.registers,sizeof(execution)));
        assert(!memcmp(&control,&t.fs.registers,sizeof(control)));
        md_proc_file_release(t.proc.file);
        assert(!buffer_release(&t) && !md_shm_space_release(t.shm_space));
        md_memory_release(t.memory);
        thread_index[t.pid%THREAD_BUCKETS]=NULL;
    }
    checkpoints.trace=checkpoint_native_trace;
    debug_reply_outcome=MD_ENTRY_NEEDS_STOP; debug_reply_value=RESTART_SIGNAL;
    puts("PASS mapping admission: typed preparation, queued turn, rejection and cancellation without register transport");
}
static void signal_group_resource(void) {
    struct thread *saved_threads=threads;
    struct md_task_registry *saved_tasks=tasks;
    tasks=md_task_registry_create(); assert(tasks);
    for (unsigned host=0;host<2;++host) {
        struct md_interception_abi abi={.raw_gate=0x2000000040,.host_policy=host};
        struct md_interception_program program;
        md_interception_program_build(&abi,&program);
        struct sock_fprog filter={program.count,program.code};
        for (unsigned original=0;original<2;++original) {
            struct seccomp_data call={.nr=SYS_rt_tgsigqueueinfo,.arch=AUDIT_ARCH_AARCH64,
                .instruction_pointer=abi.raw_gate+original*4};
            uint32_t decision;
            assert(!md_seccomp_program_eval(&filter,&call,&decision));
            assert(original ? decision!=SECCOMP_RET_ALLOW : decision==SECCOMP_RET_ALLOW);
        }
        const unsigned services[]={MD_GUEST_PID_SIGNAL_GROUP,MD_GUEST_FD_CHANNEL,MD_GUEST_BUFFER,
            MD_GUEST_PROC_IMAGE,MD_GUEST_SHM,MD_GUEST_IPC,MD_GUEST_MAP_IMAGE};
        for (unsigned i=0;i<sizeof(services)/sizeof(*services);++i) {
            struct seccomp_data call={.nr=SYS_prctl,.arch=AUDIT_ARCH_AARCH64,
                .instruction_pointer=abi.raw_gate,.args={services[i]}};
            uint32_t result;
            assert(!md_seccomp_program_eval(&filter,&call,&result) && result==SECCOMP_RET_USER_NOTIF);
        }
        struct md_entry_channel channel={.call={.number=MD_GUEST_PID_SIGNAL_GROUP,
            .reserved=MD_ENTRY_RESOURCE_CONTROL}};
        struct thread t={.pid=getpid(),.tgid=getpid(),.born=1,.phase=DISPATCHING,
            .signals.host=host,.entry.execution=&channel,.execution.request={.nr=SYS_kill}};
        threads=&t; thread_index[t.pid%THREAD_BUCKETS]=&t;
        memset(&t.execution.registers,0xa5,sizeof(t.execution.registers));
        struct user_pt_regs untouched=t.execution.registers;
        const unsigned long args[4]={0};
        checkpoints.trace=no_trace;
        long selected=(long)t.pid<<1;
        assert(resource_control(&t,MD_GUEST_PID_SIGNAL_GROUP,0,args)==selected);
        debug_stop_reply=&channel; debug_reply_outcome=MD_ENTRY_COMPLETED; debug_reply_value=selected;
        entry_control(t.pid,&channel); assert(!debug_stop_reply);
        assert(!resource_control(&t,MD_GUEST_PID_SIGNAL_GROUP,0,(unsigned long[4]){0,t.pid}));
        assert(resource_control(&t,MD_GUEST_PID_SIGNAL_GROUP,1,args)==-EINVAL);
        assert(resource_control(&t,MD_GUEST_PID_SIGNAL_GROUP,0,(unsigned long[4]){SIGKILL})==-EINVAL);
        t.execution.request.nr=SYS_getpid;
        assert(resource_control(&t,MD_GUEST_PID_SIGNAL_GROUP,0,args)==-EINVAL);
        t.phase=IDLE;
        assert(resource_control(&t,MD_GUEST_PID_SIGNAL_GROUP,0,args)==-EPERM);
        assert(!memcmp(&untouched,&t.execution.registers,sizeof(untouched)));
        thread_index[t.pid%THREAD_BUCKETS]=NULL;
    }
    checkpoints.trace=checkpoint_native_trace;
    debug_reply_outcome=MD_ENTRY_NEEDS_STOP; debug_reply_value=RESTART_SIGNAL;
    md_task_registry_destroy(tasks); tasks=saved_tasks; threads=saved_threads;
    puts("PASS group signal service: app/shell notification admission, shared owner, validated operands and no register access");
}
static void thread_exit_target(void) {
    for (unsigned translated=0;translated<2;++translated) for (unsigned target=0;target<3;++target) {
        uintptr_t address=target==0 ? 0 : target==1 ? 1 : 0x2000;
        struct md_entry_channel channel={.call={.number=SYS_set_tid_address,.args={address}}};
        struct thread t={.pid=123,.tgid=123,.entry={.control=&channel,.admission=1}};
        struct md_pid_namespace *space=md_pid_namespace_new(NULL,NULL); assert(space);
        struct md_pid_identity *identity=NULL;
        assert(!md_pid_identity_prepare(space,&identity) && !md_pid_identity_publish(identity,t.pid,t.tgid));
        if (translated) t.resources.pid=t.resources.pid_group=identity;
        memset(&t.execution.registers,0xa5,sizeof(t.execution.registers));
        struct user_pt_regs untouched=t.execution.registers;
        struct seccomp_data call={.nr=SYS_set_tid_address,.instruction_pointer=0x1004,.args={address}};
        unsigned long cookie=MD_INTERCEPT_PROCESS;
        thread_index[t.pid%THREAD_BUCKETS]=&t; checkpoints.trace=no_trace;
        if (!translated) {
            debug_stop_reply=&channel; debug_reply_outcome=MD_ENTRY_COMPLETED; debug_reply_value=0;
        }
        if (!process_call(&t,&call,NULL,&cookie)) native_continue(&t);
        if (translated) {
            assert(t.entry.effect && !t.entry.waiting && !t.entry.stop.resume);
            assert(channel.native.number==SYS_set_tid_address && channel.native.args[0]==address);
            channel.call.reserved=MD_ENTRY_EFFECT_DONE;
            channel.native.result=t.pid; atomic_store(&channel.native.state,MD_NATIVE_DONE);
            debug_stop_reply=&channel; debug_reply_outcome=MD_ENTRY_COMPLETED; debug_reply_value=1;
            entry_control(t.pid,&channel);
        } else {
            assert(channel.execution_action==MD_EXECUTION_NATIVE && channel.call.number==SYS_set_tid_address);
            assert(channel.call.args[0]==address);
        }
        assert(!debug_stop_reply && !t.entry.effect && !t.entry.control && !t.entry.stop.resume);
        assert(!memcmp(&untouched,&t.execution.registers,sizeof(untouched)));
        thread_index[t.pid%THREAD_BUCKETS]=NULL;
        md_pid_identity_release(identity); md_pid_namespace_release(space);
    }
    checkpoints.trace=checkpoint_native_trace;
    debug_reply_outcome=MD_ENTRY_NEEDS_STOP; debug_reply_value=RESTART_SIGNAL;
    puts("PASS thread exit target: native task effect, pointer preservation and PID translation without registers");
}
static int debug_wait_parent(pid_t child) { (void)child; return getpid(); }
static int debug_wait_group(pid_t pid) { return pid; }
static void debugger_shared_waits(void) {
    struct md_debugger *saved=debugger;
    const struct md_debugger_host host={.wait_children=wait_test_children,.complete=wait_test_complete,
        .wait=debugger_wait,.wait_admit=debugger_wait_admit,.wait_wake=debugger_wait_wake,
        .parent=debug_wait_parent,.group=debug_wait_group,.reaped=debug_wait_reaped};
    for (unsigned id=0;id<2;++id) for (unsigned mode=0;mode<5;++mode) {
        struct md_entry_channel channel={0};
        struct thread t={.pid=getpid(),.tgid=getpid(),.memory=md_memory_new(),
            .entry={.control=&channel,.admission=1}};
        assert(t.memory && !md_memory_buffers_prepare(t.memory,1,sizeof(union md_process_wait),0,0));
        assert(!md_memory_buffers_mapped(t.memory,0x100000,md_buffers_size(md_memory_buffers(t.memory))));
        debugger=md_debugger_create(&host); assert(debugger);
        thread_index[t.pid%THREAD_BUCKETS]=&t;
        checkpoints.trace=no_trace;
        wait_test_hidden=1; wait_test_last=1; wait_test_done=0; debug_reaped_child=0;
        struct seccomp_data call=id
            ? (struct seccomp_data){.nr=SYS_waitid,.args={P_ALL,0,0,WEXITED}}
            : (struct seccomp_data){.nr=SYS_wait4,.args={(uint64_t)-1,0,0}};
        memset(&t.execution.registers,0xa5,sizeof(t.execution.registers));
        struct user_pt_regs untouched=t.execution.registers;
        assert(debugger_call(&t,&call,NULL)==MD_DEBUG_CALL_HANDLED);
        assert(t.entry.effect==debugger_entry_probed && pid_collection_waiting(&t));
        assert(atomic_load(&channel.state)==MD_ENTRY_NATIVE && channel.native.number==call.nr);
        assert(channel.native.args[id ? 3 : 2]&WNOHANG);
        assert(channel.native.args[id ? 2 : 1]>=0x100000);
        if (mode==1 || mode==2) {
            channel.native.result=0; atomic_store(&channel.native.state,MD_NATIVE_DONE);
            channel.call.reserved=MD_ENTRY_EFFECT_DONE; entry_control(t.pid,&channel);
            assert(t.entry.wait.complete==debugger_entry_woken && !t.entry.effect && !wait_test_done);
            assert(!t.debug.wait_output && !pid_collection_waiting(&t));
            if (mode==2) { debug_stop_reply=&channel; entry_wait_complete(&t,-EINTR); }
            else {
                /* A synchronous wake can consume/remove its own model node. */
                wait_test_last=0;
                md_debugger_child_changed(debugger,1);
                assert(wait_test_done==1 && wait_test_result==-ECHILD);
            }
        } else {
            struct md_buffer_span span; assert(!md_buffer_span(&t.debug.wait_output->buffer,&span));
            union md_process_wait *result=span.data;
            if (id) result->info=(siginfo_t){.si_signo=SIGCHLD,.si_pid=1,.si_code=CLD_EXITED};
            else result->status=7<<8;
            channel.native.result=mode ? -EINTR : id ? 0 : 1;
            if (mode==3) debug_stop_reply=&channel;
            atomic_store(&channel.native.state,mode==3 ? MD_NATIVE_CANCELLED : MD_NATIVE_DONE);
            channel.call.reserved=MD_ENTRY_EFFECT_DONE; entry_control(t.pid,&channel);
            if (mode!=3) {
                assert(wait_test_done==1 && wait_test_result==(mode ? -EINTR : id ? 0 : 1));
                assert(debug_reaped_child==(mode ? 0 : 1));
            }
        }
        if (mode==2 || mode==3) {
            assert(!debug_stop_reply && !wait_test_done && !t.entry.control && t.entry.stop.resume==entry_restart_stopped);
            assert(t.entry.stop.reason==MD_CONTEXT_SIGNAL && t.execution.restart==RESTART_SIGNAL);
        }
        assert(!__real_md_debugger_active(debugger) && !t.entry.wait.complete && !t.entry.effect
            && !t.debug.wait_output && !t.retries[0].mask && !t.retries[1].mask);
        assert(!memcmp(&untouched,&t.execution.registers,sizeof(untouched)));
        assert(!memcmp(&call,&t.execution.request,sizeof(call)));
        thread_index[t.pid%THREAD_BUCKETS]=NULL; md_memory_release(t.memory);
        md_debugger_destroy(debugger);
    }
    checkpoints.trace=checkpoint_native_trace; debugger=saved;
    puts("PASS shared debugger waits: native probes, event wake, NULL output reaping, cancelled-only restart and no GPR access");
}
static long close_test_result;
static unsigned close_test_disposed,close_test_completed;
static long close_test_resume(struct md_control_pending *pending,void *data,size_t size) {
    assert(pending && !data && !size); return close_test_result;
}
static void close_test_dispose(struct md_control_pending *pending) {
    assert(pending); ++close_test_disposed;
}
static void close_test_complete(struct thread *t,long result) {
    assert(!t->entry.wait.complete && !t->close.pending && !t->close.complete);
    close_test_result=result; ++close_test_completed;
}
static void close_shared_completion(void) {
    for (unsigned cancel=0;cancel<2;++cancel) for (unsigned errors=0;errors<2;++errors) {
        struct md_entry_channel channel={.call={.reserved=MD_ENTRY_EFFECT_DONE}};
        struct thread t={.pid=123,.entry={.control=&channel,.admission=1},
            .close={.complete=close_test_complete,.errors=errors}};
        struct md_control_pending pending={.resume=close_test_resume,.dispose=close_test_dispose};
        t.close.pending=&pending;
        close_test_result=-EINPROGRESS; close_test_disposed=close_test_completed=0;
        close_progress(&t);
        assert(t.close.pending && !t.close.done && ((t.retries[0].mask|t.retries[1].mask)&RETRY_CLOSE));
        assert(entry_wait_begin(&t,INT64_MAX,close_entry_ready));
        if (cancel) entry_wait_complete(&t,-EINTR);
        else { close_test_result=-EIO; close_progress(&t); }
        assert(close_test_result==(errors ? (cancel ? -EINTR : -EIO) : 0));
        assert(close_test_disposed==1 && close_test_completed==1 && !t.close.done);
        assert(!t.retries[0].mask && !t.retries[1].mask && !entry_wait_deadline_owner());
        entry_wait_complete(&t,-EINTR);
        assert(close_test_completed==1);
    }
    puts("PASS shared close completion: provider errors, interruption and exactly-once retirement without registers");
}
static long watch_mask_trace(int request,pid_t pid,void *address,void *data) {
    assert(request==PTRACE_GETSIGMASK && pid==123 && address && data);
    ++fd_resume_calls; errno=ESRCH; return -1;
}
static void native_ioctl_route(void) {
    const unsigned commands[]={FIONBIO,0x12345678,FIONREAD};
    for (unsigned i=0;i<sizeof(commands)/sizeof(*commands);++i) {
        struct thread t={.pid=123,.phase=IDLE,.ready=1,.seccomp.application_filter=1};
        t.resources.files=md_fd_origins_new(); assert(t.resources.files);
        struct user_pt_regs regs={0}; regs.regs[8]=SYS_ioctl; regs.regs[0]=5;
        regs.regs[1]=commands[i]; regs.regs[2]=1;
        fd_expected_resume=PTRACE_CONT; fd_resume_calls=0;
        checkpoints.trace=i==2 ? watch_mask_trace : fd_resume_trace;
        thread_index[t.pid%THREAD_BUCKETS]=&t;
        struct seccomp_data call=syscall_description(&regs);
        proc_lookup_begin(&t,&call,&regs);
        assert(fd_resume_calls==1 && !t.proc.lookup.table && t.phase==IDLE);
        assert(t.resume_lost==(i==2));
        checkpoints.trace=checkpoint_native_trace; thread_index[t.pid%THREAD_BUCKETS]=NULL;
        md_fd_origins_release(t.resources.files);
    }
    puts("PASS ioctl routing: original native continuation, FIONREAD retains watch admission");
}
static void readiness_admission_identity(void) {
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    struct readiness_owner owner={.loans={{.number=-1},{.number=-1}}};
    struct thread t={.readiness=&owner};
    struct seccomp_data call={.nr=SYS_epoll_pwait,.args={3,0x1234,8,100,0x4567,8}};
    t.execution.request.nr=call.nr;
    for (unsigned i=0;i<6;++i) t.execution.request.args[i]=call.args[i];
    assert(!readiness_admission_purpose(&t,&call));
    assert(md_fd_order_begin(files,&owner.files,NULL)==1 && !md_fd_order_start(&owner.files));
    assert(readiness_admission_purpose(&t,&call)==MD_FS_BUFFER_EPOLL_ADMIT);
    for (unsigned i=0;i<6;++i) {
        ++call.args[i]; assert(!readiness_admission_purpose(&t,&call)); --call.args[i];
    }
    ++call.nr; assert(!readiness_admission_purpose(&t,&call)); --call.nr;
    owner.done=1; assert(!readiness_admission_purpose(&t,&call)); owner.done=0;
    assert(!md_fd_order_complete(&owner.files));
    struct task_action retirement={0}; owner.loans[0].retirement=&retirement;
    assert(readiness_admission_purpose(&t,&call)==MD_FS_BUFFER_EPOLL_LOAN);
    owner.loans[0].number=9; assert(!readiness_admission_purpose(&t,&call));
    t.readiness=NULL; assert(!readiness_admission_purpose(&t,&call));
    md_fd_origins_release(files);
    puts("PASS readiness admission: exact call identity, capture/loan ownership and completed-call rejection");
}
static void entry_fence_ownership(void) {
    int previous=fd_ready;
    fd_ready=eventfd(0,EFD_NONBLOCK|EFD_CLOEXEC); assert(fd_ready>=0);
    struct thread a={.pid=123,.entry.fence_waiting=1,.entry.fence_status=17},
        b={.pid=124,.entry.fence_waiting=1,.entry.fence_status=19};
    a.next=&b; threads=&a; entry_fence_waiters=2;
    entry_fence_memory=md_memory_new(); assert(entry_fence_memory);
    a.memory=b.memory=entry_fence_memory;
    entry_fence_stage=2; entry_fence_error=-EBUSY;
    int status; struct rusage usage;
    assert(entry_fence_take(&status,&usage)==123 && status==17);
    assert(a.entry.fence_result==-EBUSY && !a.entry.fence_waiting && entry_fence_waiters==1);
    assert(entry_fence_owner==&a);
    assert(!entry_fence_take(&status,&usage));
    entry_fence_owner=NULL;
    /* Exit/exec cancels a waiter exactly once, before its slot can be reused. */
    entry_fence_cancel(&b); entry_fence_cancel(&b);
    assert(!entry_fence_waiters && !entry_fence_take(&status,&usage));
    assert(!entry_fence_memory && !entry_fence_stage);
    threads=NULL;
    entry_fence_progress();
    assert(!entry_fence_memory);
    struct thread peer={.phase=DISPATCHING,.initial_stop=1};
    entry_fence_memory=md_memory_new(); assert(entry_fence_memory);
    peer.memory=entry_fence_memory; entry_fence_stage=2;
    assert(!entry_fence_boundary(&peer) && !entry_fence_adapt(&peer));
    peer.phase=IDLE;
    assert(entry_fence_boundary(&peer) && entry_fence_adapt(&peer));
    peer.machine.draining=1; assert(!entry_fence_adapt(&peer)); peer.machine.draining=0;
    peer.fs.direct=DIRECT_RESULT; assert(!entry_fence_adapt(&peer)); peer.fs.direct=DIRECT_NONE;
    peer.entry.fence_waiting=1; assert(!entry_fence_adapt(&peer)); peer.entry.fence_waiting=0;
    assert(entry_fence_adapt(&peer));
    entry_fence_stage=3; assert(!entry_fence_adapt(&peer));
    assert(!entry_fence_adapt(&peer));
    struct readiness_owner wait={.native_caller=1};
    peer.readiness=&wait; peer.phase=EPOLL_WAIT; entry_fence_stage=2;
    assert(entry_fence_drained(&peer) && !entry_fence_boundary(&peer) && !entry_fence_adapt(&peer));
    wait.evicting=1; assert(!entry_fence_drained(&peer)); wait.evicting=0;
    wait.finishing=1; assert(!entry_fence_drained(&peer)); wait.finishing=0;
    peer.machine.draining=1; assert(!entry_fence_drained(&peer)); peer.machine.draining=0;
    peer.phase=EPOLL_CALL; assert(!entry_fence_drained(&peer));
    peer.phase=SOCKET_WAITING; assert(!entry_fence_drained(&peer));
    peer.readiness=NULL; peer.phase=WATCH_READ; peer.watch_wait=2;
    assert(entry_fence_drained(&peer) && !entry_fence_boundary(&peer));
    peer.machine.syscall_exit=1; assert(!entry_fence_drained(&peer)); peer.machine.syscall_exit=0;
    peer.watch_wait=1; assert(!entry_fence_drained(&peer));
    peer.watch_wait=0; assert(entry_fence_drained(&peer));
    peer.watch_wait=2; peer.phase=WATCH_ENTRY; assert(!entry_fence_drained(&peer));
    peer.phase=PROCESS_OBSERVING;
    const long waits[]={SYS_wait4,SYS_waitid};
    for (unsigned i=0;i<sizeof(waits)/sizeof(*waits);++i) {
        peer.execution.request.nr=waits[i]; assert(entry_fence_drained(&peer));
        peer.machine.draining=1; assert(!entry_fence_drained(&peer)); peer.machine.draining=0;
    }
    peer.execution.request.nr=SYS_rt_sigtimedwait; assert(!entry_fence_drained(&peer));
    peer.execution.request.nr=SYS_process_vm_writev; assert(!entry_fence_drained(&peer));
    struct md_entry_channel channel={0};
    peer.phase=DISPATCHING; peer.entry.execution=&channel;
    peer.entry.control=&channel; peer.entry.effect=entry_watch_ready; peer.entry.waiting=1;
    assert(entry_fence_drained(&peer));
    peer.entry.waiting=0; assert(!entry_fence_drained(&peer)); peer.entry.waiting=1;
    peer.fs.direct=DIRECT_RESULT; assert(!entry_fence_drained(&peer)); peer.fs.direct=DIRECT_NONE;
    peer.machine.draining=1; assert(!entry_fence_drained(&peer)); peer.machine.draining=0;
    peer.descriptors.running=1; assert(!entry_fence_drained(&peer)); peer.descriptors.running=0;
    peer.readiness=&wait;
    assert(entry_fence_drained(&peer));
    wait.evicting=1; assert(!entry_fence_drained(&peer)); wait.evicting=0;
    wait.finishing=1; assert(!entry_fence_drained(&peer)); wait.finishing=0;
    wait.files.table=(void *)&wait; assert(!entry_fence_drained(&peer)); wait.files.table=NULL;
    wait.publication_memory.queue=(void *)&wait;
    assert(!entry_fence_drained(&peer)); wait.publication_memory.queue=NULL;
    assert(entry_fence_drained(&peer));
    md_memory_release(entry_fence_memory); entry_fence_memory=NULL; entry_fence_stage=0;
    close(fd_ready); fd_ready=previous;
    puts("PASS supervisor entry fence: waiter retirement, transition ownership, private drain and admission hold");
}
static void control_deadlines(void) {
    struct md_control_pending late={.deadline=500},early={.deadline=100};
    struct thread a={.pid=123,.control.pending=&late},b={.pid=124,.control.pending=&early},idle={.pid=125};
    assert(!control_deadline_owner());
    retry_wait(&idle,RETRY_PROC_IO);
    retry_wait(&a,RETRY_CONTROL);
    assert(control_deadline_owner()==&a);
    /* Requeued work and the still-dispatching generation both retain bounds. */
    retry_queues[1].current^=1;
    retry_wait(&b,RETRY_CONTROL);
    assert(control_deadline_owner()==&b);
    early.deadline=600;
    assert(control_deadline_owner()==&a);
    retry_wait(&a,RETRY_PROC_IO);
    retry_clear(&a,RETRY_CONTROL);
    assert(control_deadline_owner()==&b && a.retries[1].mask==RETRY_PROC_IO);
    retry_clear(&b,RETRY_CONTROL); retry_clear(&b,RETRY_CONTROL);
    assert(!control_deadline_owner());
    retry_clear(&a,RETRY_PROC_IO); retry_clear(&idle,RETRY_PROC_IO);
    retry_queues[1].current=0;
    assert(!retry_queues[1].lists[0].first && !retry_queues[1].lists[1].first);
    puts("PASS control deadlines: active continuations only, both generations, reschedule and exact cancellation");
}
static long entry_wait_result;
static void entry_wait_test_complete(struct thread *t,long value) {
    assert(!t->entry.wait.complete && !t->entry.wait.previous);
    entry_wait_result=value;
}
static void entry_wait_ownership(void) {
    struct md_entry_channel channel={0};
    struct thread a={.entry={.control=&channel,.execution=&channel}},b=a,c=a;
    assert(!entry_wait_deadline_owner());
    assert(entry_wait_begin(&a,500,entry_wait_test_complete));
    assert(atomic_load(&channel.state)==MD_ENTRY_PARKED);
    assert(entry_wait_begin(&b,100,entry_wait_test_complete));
    assert(entry_wait_begin(&c,INT64_MAX,entry_wait_test_complete));
    assert(entry_wait_deadline_owner()==&b && !c.entry.wait.previous);
    assert(entry_wait_begin(&a,50,entry_wait_test_complete));
    assert(entry_wait_deadline_owner()==&a);
    assert(entry_wait_begin(&a,50,entry_wait_test_complete));
    entry_wait_complete(&a,-ETIMEDOUT);
    assert(entry_wait_result==-ETIMEDOUT && entry_wait_deadline_owner()==&b);
    entry_wait_complete(&c,-EINTR);
    assert(entry_wait_result==-EINTR && entry_wait_deadline_owner()==&b);
    entry_wait_end(&b); entry_wait_end(&b);
    assert(!entry_wait_deadline_owner());
    siginfo_t signal={.si_signo=SIGUSR1,.si_code=SI_USER,.si_pid=getpid()};
    assert(!md_signal_delivery_hold(&a.signals.delivery,&signal));
    assert(entry_wait_begin(&a,40,entry_wait_test_complete));
    assert(entry_wait_result==-EINTR && !a.entry.wait.complete && !entry_wait_deadline_owner());
    md_signal_delivery_clear(&a.signals.delivery);
    a.entry.execution=NULL;
    assert(!entry_wait_begin(&a,0,entry_wait_test_complete));
    assert(!a.entry.wait.complete);
    a.entry.admission=1; channel.call.reserved=MD_ENTRY_EFFECT_DONE;
    assert(entry_wait_begin(&a,INT64_MAX,entry_wait_test_complete));
    entry_wait_complete(&a,-EINTR);
    assert(entry_wait_result==-EINTR && !a.entry.wait.complete);
    puts("PASS resident waits: finite deadline ordering, infinite waits, cancellation and exact retirement");
}
static void pid_collection_waiters(void) {
    const struct { enum phase phase; int wait_result; int effect; } states[]={
        {PROCESS_OBSERVING,0,0},{DISPATCHING,1,0},{DISPATCHING,2,0},
        {IDLE,0,1},{DEBUG_WAIT_ENTRY,0,0},{DEBUG_WAIT_EXIT,0,0}};
    const pid_t retired=INT_MAX;
    struct md_identity identity={0};
    struct thread first={.pid=123},last={.pid=124};
    first.next=&last; threads=&first;
    tasks=md_task_registry_create(); assert(tasks && pid_gone(retired));
    for (unsigned i=0;i<sizeof(states)/sizeof(*states);++i) {
        assert(!md_task_registry_publish(tasks,retired,retired,&identity,NULL));
        md_task_registry_retire(tasks,retired,0,&identity);
        first.phase=IDLE; first.process_op.wait_result=0;
        last.pid=124; last.phase=states[i].phase; last.process_op.wait_result=states[i].wait_result;
        last.entry.effect=states[i].effect ? process_entry_complete : NULL;
        pid_collection_pending=1; pid_collect();
        assert(pid_collection_pending && pid_collection_blocker==&last);
        assert(md_task_registry_has_retired(tasks));
        /* An earlier waiter must not replace a still-valid hint on each event. */
        first.phase=DISPATCHING; first.process_op.wait_result=1;
        for (unsigned event=0;event<1000;++event) {
            pid_collect(); assert(pid_collection_blocker==&last);
        }
        last.phase=IDLE; last.process_op.wait_result=0; last.entry.effect=NULL; pid_collect();
        assert(pid_collection_pending && pid_collection_blocker==&first);
        /* An exited slot is not a waiter, but a reused live slot can be one. */
        first.pid=0; last.pid=125; last.phase=states[i].phase;
        last.process_op.wait_result=states[i].wait_result;
        last.entry.effect=states[i].effect ? process_entry_complete : NULL;
        pid_collect();
        assert(pid_collection_pending && pid_collection_blocker==&last);
        last.pid=0; pid_collect();
        assert(!pid_collection_pending && !pid_collection_blocker);
        assert(!md_task_registry_has_retired(tasks));
        first.pid=123;
    }
    assert(!md_task_registry_publish(tasks,retired,retired,&identity,NULL));
    md_task_registry_retire(tasks,retired,0,&identity);
    first.phase=DEBUG_WAIT_EXIT; pid_collection_pending=1; pid_collect();
    assert(pid_collection_blocker==&first);
    md_task_registry_reap(tasks,retired); pid_collect();
    assert(!pid_collection_pending && !pid_collection_blocker);
    md_task_registry_destroy(tasks); tasks=NULL; threads=NULL;
    puts("PASS PID collection: retained waiter, phase changes, exit, slot reuse and explicit reap");
}
struct completion_probe { struct thread *task; size_t copied; unsigned commits; };
static void *completion_retain(void *p) { return p; }
static void completion_release(void *p) { (void)p; }
static int completion_access(void *p,const struct md_identity *id,unsigned mode) {
    (void)p; (void)id; (void)mode; return 0;
}
static long completion_read(void *p,const struct md_control_actor *actor,void *bytes,size_t size,int64_t offset) {
    (void)p; (void)actor; assert(size>=4 && !offset); memcpy(bytes,"data",4); return 4;
}
static long completion_write(void *p,const struct md_control_actor *actor,const void *bytes,size_t size,int64_t offset) {
    (void)p; (void)actor; assert(size==4 && !offset && !memcmp(bytes,"data",4)); return 4;
}
static void completion_commit(void *context,size_t copied) {
    struct completion_probe *p=context;
    struct md_buffer_span span;
    assert(!md_buffer_span(&p->task->fs.buffer,&span) && !memcmp(span.data,"data",4));
    ++p->commits; p->copied=copied;
}
static void terminal_buffer_completion(void) {
    const struct md_control_file_ops ops={.retain=completion_retain,.release=completion_release,
        .access=completion_access,.read=completion_read,.write=completion_write,.read_commit=completion_commit};
    struct md_interception_buffers *pool=NULL;
    assert(!md_buffers_create(1,4096,0,0,&pool));
    for (unsigned terminal=0;terminal<2;++terminal) for (unsigned mode=0;mode<5;++mode) {
        struct thread t={.fs.open.fd=-1,.proc.image_fd=-1,.control.mapping_fd=-1};
        struct completion_probe probe={.task=&t};
        assert(!md_buffers_take(pool,&t.fs.buffer));
        struct md_proc_file *file=NULL;
        if (mode!=3) {
            file=md_proc_file_open_control(&ops,&probe,&(struct md_control_opener){.identity=&t.identity},O_RDONLY); assert(file);
            assert(md_proc_file_io_begin(&t.proc.io,file,MD_PROC_FILE_READ,0,0,0,4,NULL)==1);
            struct md_buffer_span span; assert(!md_buffer_span(&t.fs.buffer,&span));
            assert(md_proc_file_io_execute(&t.proc.io,&t.identity,NULL,span.data,span.size,4096)==4);
            assert(!md_proc_file_io_offer(&t.proc.io));
        }
        struct outgoing_owner busy={.stage=OUTGOING_SEND};
        if (mode==4) t.io.outgoing=&busy;
        long copied=mode==1 ? 2 : mode==2 ? -EFAULT : 4;
        if (terminal) {
            long result=buffer_result_finish(&t,copied,copied);
            assert(result==(mode==3 ? -EINVAL : mode==4 ? -EBUSY : copied));
        } else {
            struct buffer_completion result=buffer_finish(&t,copied);
            assert(result.value==(mode==3 ? -EINVAL : 0));
            assert(result.released==(mode==4 ? -EBUSY : 0));
        }
        assert(probe.commits==(mode!=3) && probe.copied==(mode==3 || copied<0 ? 0 : (size_t)copied));
        assert(!t.proc.io.file && !!t.fs.buffer.pool==(mode==4));
        t.io.outgoing=NULL;
        assert(!buffer_cleanup(&t));
        md_proc_file_release(file);
        /* The sole slot is reusable immediately, including a failed finish. */
        assert(!md_buffers_take(pool,&t.fs.buffer) && !md_buffer_release(&t.fs.buffer));
    }
    for (unsigned seek=0;seek<2;++seek) {
        struct thread t={.fs.open.fd=-1,.proc.image_fd=-1,.control.mapping_fd=-1};
        struct completion_probe probe={.task=&t};
        assert(!md_buffers_take(pool,&t.fs.buffer));
        struct md_proc_file *file=md_proc_file_open_control(&ops,&probe,&(struct md_control_opener){.identity=&t.identity},O_RDWR); assert(file);
        assert(md_proc_file_io_begin(&t.proc.io,file,seek ? MD_PROC_FILE_SEEK : MD_PROC_FILE_WRITE,
            0,seek ? 7 : 0,SEEK_SET,seek ? 0 : 4,NULL)==1);
        struct md_buffer_span span; assert(!md_buffer_span(&t.fs.buffer,&span));
        memcpy(span.data,"data",4);
        long expected=seek ? 7 : 4;
        assert(md_proc_file_io_execute(&t.proc.io,&t.identity,NULL,span.data,span.size,4096)==expected);
        assert(!md_proc_file_io_offer(&t.proc.io));
        assert(buffer_result_finish(&t,0,expected)==expected);
        assert(!probe.commits && !t.proc.io.file && !t.fs.buffer.pool);
        md_proc_file_release(file);
    }
    for (unsigned mode=0;mode<3;++mode) {
        struct thread t={.fs.open.fd=-1,.proc.image_fd=-1,.control.mapping_fd=-1};
        assert(!md_buffers_take(pool,&t.fs.buffer));
        long result=mode==0 ? -EAGAIN : mode==1 ? 32 : MD_CALL_NATIVE;
        assert(buffer_result_finish(&t,0,result)==result && !t.fs.buffer.pool);
        assert(!md_buffers_take(pool,&t.fs.buffer) && !md_buffer_release(&t.fs.buffer));
    }
    md_buffers_release(pool);
    puts("PASS terminal buffer completion: commit before release, partial copy, EFAULT, failed finish and independent cleanup error");
}
struct ioctl_publication_probe {
    struct md_control_pending pending;
    struct thread *task;
    unsigned copied, disposed;
    long result;
};
static void ioctl_publication_copied(struct md_control_pending *pending,long result) {
    struct ioctl_publication_probe *p=(void *)pending;
    struct md_buffer_span span;
    assert(!p->copied && !p->disposed && !p->task->control.publication);
    assert(!md_buffer_span(&p->task->fs.buffer,&span));
    assert(!memcmp(span.data,"reply",6));
    ++p->copied; p->result=result;
}
static void ioctl_publication_dispose(struct md_control_pending *pending) {
    struct ioctl_publication_probe *p=(void *)pending;
    assert(!p->disposed); ++p->disposed;
}
static void ioctl_publication_completion(void) {
    struct md_interception_buffers *pool=NULL;
    assert(!md_buffers_create(1,4096,0,0,&pool));
    for (unsigned mode=0;mode<6;++mode) {
        struct thread t={.fs.open.fd=-1,.proc.image_fd=-1,.control.mapping_fd=-1};
        struct ioctl_publication_probe p={.pending={.copied=ioctl_publication_copied,
            .dispose=ioctl_publication_dispose},.task=&t};
        assert(!md_buffers_take(pool,&t.fs.buffer));
        struct md_buffer_span span; assert(!md_buffer_span(&t.fs.buffer,&span));
        memcpy(span.data,"reply",6);
        t.control.pending=&p.pending;
        if (mode==5) p.pending.copied=NULL;
        control_pending_complete(&t,mode==4 ? -ENOENT : 0);
        assert(!t.control.pending && !!t.control.publication==(mode<4));
        assert(p.disposed==(mode>=4) && !p.copied);
        long result=mode==1 ? -EFAULT : 0;
        if (mode==2) assert(!buffer_cleanup(&t));
        else if (mode==3) {
            control_publication_finish(&t,-ECANCELED);
            assert(!buffer_cleanup(&t));
        } else assert(buffer_result_finish(&t,0,result)==result);
        assert(p.disposed==1 && p.copied==(mode<4) && !t.control.publication);
        if (mode<4) assert(p.result==(mode>=2 ? -ECANCELED : result));
        control_publication_finish(&t,0);
        assert(!buffer_cleanup(&t) && p.disposed==1);
        assert(!md_buffers_take(pool,&t.fs.buffer) && !md_buffer_release(&t.fs.buffer));
    }
    md_buffers_release(pool);
    assert(md_control_ioctl_size(&(struct md_control_ioctl){.size=8,.direction=MD_CONTROL_ACK})==-EPROTO);
    assert(md_control_ioctl_size(&(struct md_control_ioctl){.size=8,.direction=MD_CONTROL_ACK|MD_CONTROL_OUT})==8);
    struct md_control_ioctl captured={.size=12,.direction=MD_CONTROL_IN|MD_CONTROL_FD,.descriptor=8};
    assert(md_control_ioctl_size(&captured)==12 && md_control_ioctl_capture_offset(&captured)==16);
    captured.descriptor=9; assert(md_control_ioctl_size(&captured)==-EPROTO);
    captured.descriptor=8; captured.direction=MD_CONTROL_OUT|MD_CONTROL_FD;
    assert(md_control_ioctl_size(&captured)==-EPROTO);
    captured.direction=MD_CONTROL_IN|MD_CONTROL_FD|MD_CONTROL_STRING;
    assert(md_control_ioctl_size(&captured)==-EPROTO);
    puts("PASS ioctl output publication: exact copy acknowledgement, EFAULT, cancellation, failure and no extra ordinary-ioctl acknowledgement");
}
static long notify_ioctl(struct md_control_file file,unsigned command,void *data,size_t size,
        struct md_control_pending **pending) {
    return file.ops->ioctl_async(file.context,NULL,command,0,data,size,pending);
}
static long notify_send(struct md_control_file file,struct seccomp_notif_resp response) {
    struct md_control_pending *pending=NULL;
    long result=notify_ioctl(file,SECCOMP_IOCTL_NOTIF_SEND,&response,sizeof(response),&pending);
    assert(!pending); return result;
}
static long notify_valid(struct md_control_file file,uint64_t id) {
    struct md_control_pending *pending=NULL;
    long result=notify_ioctl(file,SECCOMP_IOCTL_NOTIF_ID_VALID,&id,sizeof(id),&pending);
    assert(!pending); return result;
}
static void notification_queue(void) {
    struct md_seccomp_notify *q=md_seccomp_notify_new(NULL); assert(q);
    struct md_control_file file=md_seccomp_notify_control(q);
    assert(!file.ops->ioctl && md_control_ioctl_available(file));
    struct md_control_file_ops incomplete=*file.ops;
    incomplete.ioctl_async=NULL;
    assert(!md_control_ioctl_available((struct md_control_file){&incomplete,q}));
    struct md_interception_buffers *pool=NULL;
    assert(!md_buffers_create(1,4096,0,0,&pool));
    int descriptor=-1;
    assert(!file.ops->poll_descriptor(file.context,&descriptor) && descriptor>=0);
    struct pollfd watch={.fd=descriptor,.events=POLLIN};
    assert(file.ops->poll(file.context)==0 && poll(&watch,1,0)==0);
    uint64_t last=0;
    for (unsigned mode=0;mode<7;++mode) {
        struct thread t={.fs.open.fd=-1,.proc.image_fd=-1,.control.mapping_fd=-1};
        assert(!md_buffers_take(pool,&t.fs.buffer));
        struct md_buffer_span span; assert(!md_buffer_span(&t.fs.buffer,&span));
        struct seccomp_notif *received=span.data;
        memset(received,0,sizeof(*received));
        assert(notify_ioctl(file,SECCOMP_IOCTL_NOTIF_RECV,received,sizeof(*received),&t.control.pending)==-EINPROGRESS);
        assert(t.control.pending && t.control.pending->deadline==INT64_MAX);
        struct seccomp_data call={.nr=SYS_getpid,.arch=AUDIT_ARCH_AARCH64,.instruction_pointer=0x12345004,
            .args={1,2,3,4,5,6}};
        struct md_seccomp_notification *request=NULL;
        assert(!md_seccomp_notify_begin(q,456,NULL,&call,&request));
        assert(poll(&watch,1,0)==1 && file.ops->poll(file.context)==(POLLIN|POLLRDNORM));
        /* Pending receive captured zero-initialization once. A later guest
         * write cannot retarget the queued operation. */
        memset(received,0x55,sizeof(*received));
        assert(t.control.pending->resume(t.control.pending,received,sizeof(*received))==0);
        uint64_t id=received->id;
        assert(id>last && received->pid==456 && !received->flags && !memcmp(&received->data,&call,sizeof(call)));
        last=id;
        assert(file.ops->poll(file.context)==(POLLOUT|POLLWRNORM));
        assert(!notify_valid(file,id) && notify_valid(file,id+1)==-ENOENT);
        assert(notify_send(file,(struct seccomp_notif_resp){.id=id,.flags=2})==-EINVAL);
        assert(notify_send(file,(struct seccomp_notif_resp){.id=id,.flags=SECCOMP_USER_NOTIF_FLAG_CONTINUE,.val=1})==-EINVAL);
        control_pending_complete(&t,0);
        struct seccomp_notif_resp expected={.id=id,.val=123};
        if (mode==0) expected=(struct seccomp_notif_resp){.id=id,.flags=SECCOMP_USER_NOTIF_FLAG_CONTINUE};
        if (mode==1) expected=(struct seccomp_notif_resp){.id=id,.error=-EACCES};
        if (mode==4) { md_seccomp_notification_release(request); request=NULL; }
        if (mode==5) assert(!notify_send(file,expected));
        long copy=mode>=2 ? -EFAULT : 0;
        if (mode==3) assert(!buffer_cleanup(&t));
        else assert(buffer_result_finish(&t,0,copy)==copy);
        assert(!t.control.pending && !t.control.publication);
        if (mode==4) {
            assert(notify_valid(file,id)==-ENOENT);
            assert(notify_send(file,expected)==-ENOENT);
            assert(file.ops->poll(file.context)==0);
            continue;
        }
        if (mode>=2 && mode!=5) {
            assert(file.ops->poll(file.context)==(POLLIN|POLLRDNORM));
            assert(notify_valid(file,id)==-ENOENT && notify_send(file,expected)==-EINPROGRESS);
            assert(!md_buffers_take(pool,&t.fs.buffer) && !md_buffer_span(&t.fs.buffer,&span));
            received=span.data; memset(received,0,sizeof(*received));
            assert(!notify_ioctl(file,SECCOMP_IOCTL_NOTIF_RECV,received,sizeof(*received),&t.control.pending));
            assert(received->id==id);
            control_pending_complete(&t,0);
            assert(!buffer_result_finish(&t,0,0));
        }
        if (mode!=5) assert(!notify_send(file,expected));
        assert(notify_send(file,expected)==-EINPROGRESS && notify_valid(file,id)==-ENOENT);
        struct seccomp_notif_resp actual={0};
        assert(md_seccomp_notification_result(request,&actual)==1 && !memcmp(&actual,&expected,sizeof(actual)));
        md_seccomp_notification_release(request);
        assert(notify_send(file,expected)==-ENOENT && file.ops->poll(file.context)==0);
    }
    struct md_seccomp_notification *queued=NULL,*sent=NULL,*replied=NULL;
    const struct seccomp_data call={.nr=SYS_mount};
    assert(!md_seccomp_notify_begin(q,1,NULL,&call,&sent));
    assert(!md_seccomp_notify_begin(q,2,NULL,&call,&replied));
    assert(!md_seccomp_notify_begin(q,3,NULL,&call,&queued));
    for (unsigned i=0;i<2;++i) {
        struct seccomp_notif message={0}; struct md_control_pending *pending=NULL;
        assert(!notify_ioctl(file,SECCOMP_IOCTL_NOTIF_RECV,&message,sizeof(message),&pending));
        pending->copied(pending,0); pending->dispose(pending);
        if (i) assert(!notify_send(file,(struct seccomp_notif_resp){.id=message.id,.val=99}));
    }
    md_seccomp_notify_close(q); md_seccomp_notify_close(q);
    struct seccomp_notif_resp result;
    assert(md_seccomp_notification_result(queued,&result)==1 && result.error==-ENOSYS);
    assert(md_seccomp_notification_result(sent,&result)==1 && result.error==-ENOSYS);
    assert(md_seccomp_notification_result(replied,&result)==1 && result.val==99 && !result.error);
    struct md_seccomp_notification *late=NULL;
    assert(md_seccomp_notify_begin(q,4,NULL,&call,&late)==-ENOSYS && !late);
    assert(file.ops->poll(file.context)==POLLHUP);
    md_seccomp_notify_release(q);
    md_seccomp_notification_release(queued); md_seccomp_notification_release(sent); md_seccomp_notification_release(replied);
    md_buffers_release(pool);
    puts("PASS virtual notification queue: copy acknowledgement, mutable input, cancellation, committed SEND, exact IDs and last listener close");
}
static void virtual_listener_lifetime(void) {
    struct md_identity identity=md_identity_new(1000,1000);
    struct sock_filter code[]={BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_USER_NOTIF)};
    struct sock_fprog program={1,code};
    struct md_seccomp_policy *policy=NULL;
    assert(!md_seccomp_policy_capture(NULL,&program,&policy));
    struct md_fd_object *object=md_seccomp_listener_virtual(policy,
        &(struct md_control_opener){.identity=&identity},NULL); assert(object);
    assert(md_seccomp_listener_policy(object)==policy);
    struct md_fd_object *selected=md_seccomp_policy_listener(policy);
    assert(selected==object); md_fd_object_release(selected);
    struct md_proc_file *file=md_proc_file_from_object(object); assert(file);
    assert(md_proc_file_kind(file)==MD_PROC_FILE_CONTROL);
    unsigned expected=(1U<<MD_FD_SELECTOR_PROC)|(1U<<MD_FD_SELECTOR_POLL)|(1U<<MD_FD_SELECTOR_EPOLL_TARGET);
    assert(md_fd_object_selectors(object)==expected);
    int carrier=md_proc_file_descriptor(file); assert(carrier>=0); close(carrier);
    errno=0;
    assert(!md_proc_file_reopen(file,&(struct md_control_opener){.identity=&identity},O_RDWR) && errno==ENXIO);
    struct md_proc_file *path=md_proc_file_reopen(file,&(struct md_control_opener){.identity=&identity},O_PATH|O_CLOEXEC);
    assert(path && !md_seccomp_listener_notifications(md_proc_file_object(path)));
    assert(md_fd_object_selectors(md_proc_file_object(path))==(1U<<MD_FD_SELECTOR_PROC));
    assert(md_proc_file_access(path,MD_PROC_FILE_READ)==-EBADF);
    assert(md_proc_file_access(path,MD_PROC_FILE_WRITE)==-EBADF);
    struct stat before,after;
    assert(!md_proc_file_control_stat(file,&before));
    assert(md_proc_file_control_access(path,&identity,R_OK)==md_file_permissions(&before,R_OK,&identity));
    struct md_proc_file *path_copy=md_proc_file_reopen(path,&(struct md_control_opener){.identity=&identity},O_PATH);
    assert(path_copy); md_proc_file_release(path); path=path_copy;
    struct md_seccomp_notify *q=md_seccomp_listener_notifications(object); assert(q);
    struct md_control_file control=md_seccomp_notify_control(q);
    assert(control.ops->poll(control.context)&POLLHUP);
    struct md_seccomp_policy *task=NULL,*child=NULL,*descendant=NULL;
    md_seccomp_policy_replace(&task,policy);
    assert(!(control.ops->poll(control.context)&POLLHUP));
    md_seccomp_policy_replace(&child,task);
    assert(!md_seccomp_policy_capture(task,&program,&descendant));
    md_seccomp_policy_replace(&task,descendant);
    md_seccomp_policy_release(descendant);
    md_seccomp_policy_replace(&child,NULL);
    assert(!(control.ops->poll(control.context)&POLLHUP));
    md_seccomp_policy_replace(&task,NULL);
    assert(control.ops->poll(control.context)&POLLHUP);
    md_seccomp_policy_replace(&task,policy);
    struct md_seccomp_notification *request=NULL;
    assert(!md_seccomp_notify_begin(q,123,NULL,&(struct seccomp_data){.nr=SYS_gettid},&request));
    struct md_fd_origins *source=md_fd_origins_new(),*destination=md_fd_origins_new();
    assert(source && destination);
    struct md_fd_origin_update update={0};
    assert(!md_fd_origins_prepare_object(source,object,FD_CLOEXEC,&update));
    md_fd_origins_finish(&update,0);
    struct md_fd_origins *forked=md_fd_origins_fork(source,0); assert(forked);
    assert(!md_fd_origins_prepare_batch(destination,&object,FD_CLOEXEC,1,&update));
    const int received=17;
    assert(!md_fd_origins_finish_batch(&update,1,&received,1));
    struct seccomp_notif_resp result;
    md_fd_origins_release(source); md_fd_origins_release(forked);
    md_fd_object_release(object);
    assert(!md_seccomp_notification_result(request,&result));
    object=md_fd_origins_object(destination,received,NULL);
    assert(object && md_seccomp_listener_notifications(object)==q);
    md_fd_object_release(object);
    assert(!md_fd_origins_close_range(destination,received,received,0));
    assert(md_seccomp_notification_result(request,&result)==1 && result.error==-ENOSYS);
    assert(!md_seccomp_policy_listener(policy));
    assert(!md_proc_file_control_stat(path,&after) && before.st_dev==after.st_dev && before.st_ino==after.st_ino);
    errno=0;
    assert(!md_proc_file_reopen(path,&(struct md_control_opener){.identity=&identity},O_RDONLY) && errno==ENXIO);
    carrier=md_proc_file_descriptor(path);
    assert(carrier>=0 && (fcntl(carrier,F_GETFL)&O_PATH)); close(carrier);
    md_proc_file_release(path);
    md_fd_origins_release(destination); md_seccomp_notification_release(request);
    md_seccomp_policy_replace(&task,NULL);
    md_seccomp_policy_release(policy);
    md_identity_release(&identity);
    puts("PASS virtual listener FD lifetime: shared proc/ioctl/readiness owner, descriptor zero, fork, SCM_RIGHTS and last-close ENOSYS");
}
static void notification_policy_binding(void) {
    struct md_identity identity=md_identity_new(0,0);
    struct md_control_opener opener={.identity=&identity};
    struct sock_filter code[]={BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_USER_NOTIF)};
    struct sock_fprog program={1,code};
    struct md_seccomp_policy *older=NULL,*newer=NULL;
    assert(!md_seccomp_policy_capture(NULL,&program,&older));
    struct md_fd_object *listener=md_seccomp_listener_virtual(older,&opener,NULL); assert(listener);
    assert(!md_seccomp_policy_capture(older,&program,&newer));
    errno=0;
    assert(!md_seccomp_listener_virtual(newer,&opener,NULL) && errno==EBUSY);
    uint32_t action; const struct md_seccomp_policy *winner=NULL;
    assert(!md_seccomp_policy_decide(newer,&(struct seccomp_data){0},&action,&winner));
    assert(action==SECCOMP_RET_USER_NOTIF && winner==newer && !md_seccomp_policy_listener(winner));
    /* A newer unlistened filter must not deliver into an older listener. */
    struct md_fd_object *selected=md_seccomp_policy_listener(older);
    assert(selected==listener); md_fd_object_release(selected);
    md_fd_object_release(listener);
    assert(!md_seccomp_policy_listener(older));
    listener=md_seccomp_listener_virtual(newer,&opener,NULL); assert(listener);
    selected=md_seccomp_policy_listener(winner);
    assert(selected==listener); md_fd_object_release(selected);
    md_fd_object_release(listener);
    md_seccomp_policy_release(newer); md_seccomp_policy_release(older); md_identity_release(&identity);
    puts("PASS notification policy binding: newest exact winner, no ancestor substitution, duplicate rejection and weak last-close lifetime");
}
void md_test_seccomp_notify(void);
static void buffered_io_pressure(void) {
    struct thread t={.pid=123,.phase=DISPATCHING,.memory=md_memory_new(),.fs.waiting=1};
    assert(t.memory && !md_memory_buffers_prepare(t.memory,1,4096,0,0));
    struct md_interception_buffer held={0};
    assert(!md_buffers_take(md_memory_buffers(t.memory),&held));
    const unsigned commands[]={MD_BUFFER_PROC_START,MD_BUFFER_UTS,MD_BUFFER_SHM_STAT,MD_BUFFER_IPC_PREPARE};
    for (unsigned i=0;i<sizeof(commands)/sizeof(*commands);++i) {
        t.fs.request.command=commands[i]; t.fs.request.args[0]=4;
        buffer_take(&t);
        assert(t.fs.waiting && !t.fs.buffer.pool && !t.proc.io.file && !t.proc.io.turn.queue && !t.io.transport.packet);
        assert(t.retries[RETRY_BUFFER>=RETRY_FD].mask==RETRY_BUFFER);
    }
    /* Cancellation while waiting has neither provider work nor a buffer to
     * retire; freeing capacity cannot resurrect the removed continuation. */
    retry_clear(&t,RETRY_BUFFER); t.fs.waiting=0;
    assert(!md_buffer_release(&held));
    assert(!md_buffers_take(md_memory_buffers(t.memory),&held));
    assert(!md_buffer_release(&held));
    md_memory_release(t.memory);
    puts("PASS buffered IO pressure: no OFD turn before buffer admission and cancelled continuation stays retired");
}
static void signal_buffer_pressure(void) {
    struct thread t={.pid=123,.phase=SIGNAL_CONTROL,.memory=md_memory_new()};
    assert(t.memory && !md_memory_buffers_prepare(t.memory,1,4096,0,0));
    struct md_interception_buffer held={0};
    assert(!md_buffers_take(md_memory_buffers(t.memory),&held));
    for (int returning=0;returning<2;++returning) {
        t.signals.control=calloc(1,sizeof(*t.signals.control)); assert(t.signals.control);
        t.signals.control->started=t.signals.control->reacquiring=returning;
        signal_control_progress(&t);
        assert(!t.signals.control->scratch.pool && !t.signals.control->memory.queue);
        struct md_resource_order other={0};
        assert(md_memory_order_begin(t.memory,&other,NULL)==1);
        assert(!md_resource_order_start(&other) && !md_resource_order_complete(&other));
        signal_control_discard(&t);
    }
    assert(!md_buffer_release(&held)); md_memory_release(t.memory);
    puts("PASS signal buffer pressure: initial and returning waits never hold memory admission while awaiting a pool slot");
}
static void prepared_input(void) {
    struct thread t={.memory=md_memory_new(),.fs.open.fd=-1,
        .proc.image_fd=-1,.control.mapping_fd=-1};
    struct md_identity identity=md_identity_new(1000,1000);
    struct md_process_limits limits;
    assert(!md_process_limits_read(&limits));
    t.resources.process=md_process_member_new(&identity,&limits);
    assert(t.memory && t.resources.process);
    assert(!md_memory_buffers_prepare(t.memory,1,sizeof(union md_adapter_frame),0,0));
    struct md_interception_buffers *pool=md_memory_buffers(t.memory);
    t.execution.request.nr=SYS_recvmsg;
    assert(!dispatch_input(&t,0) && !t.fs.buffer.pool && !t.execution.prepared);
    assert(!md_memory_buffers_mapped(t.memory,0x100000,md_buffers_size(pool)));
    t.execution.request.nr=SYS_execve;
    assert(!dispatch_input(&t,0) && !t.fs.buffer.pool);
    strcpy(t.endpoint,"managed");
    unsigned calls[]={SYS_recvmsg,SYS_recvmmsg,SYS_ppoll,SYS_pselect6,SYS_execve,SYS_execveat};
    unsigned adapters[]={MD_DISPATCH_NETWORK_DATA,MD_DISPATCH_NETWORK_CONTROL,MD_DISPATCH_DEVICE_EVENTS};
    for (unsigned i=0;i<sizeof(calls)/sizeof(*calls);++i) {
        t.execution.request.nr=calls[i]; t.execution.request.args[0]=t.execution.request.args[1]=8;
        for (unsigned j=0;j<sizeof(adapters)/sizeof(*adapters);++j)
            assert(!dispatch_input(&t,MD_DISPATCH_PATHS|adapters[j]) && !t.fs.buffer.pool && !t.execution.prepared);
        assert(dispatch_input(&t,0)==0x100000 && t.execution.prepared);
        struct md_interception_buffer held={0};
        assert(md_buffers_take(pool,&held)==-EAGAIN);
        struct md_buffer_span span;
        assert(!md_buffer_span(&t.fs.buffer,&span));
        memset(span.data,0xa5,span.size);
        /* An entry-prepared receive keeps its exact input/result frame when
         * the guest publication continuation acquires its adapter stack. */
        assert(dispatch_input(&t,0)==0x100000 && t.execution.prepared);
        for (size_t byte=0;byte<span.size;++byte)
            assert(((unsigned char *)span.data)[byte]==0xa5);
        assert(md_buffers_take(pool,&held)==-EAGAIN);
        /* Early validation failure and ordinary FINISH share exact cleanup. */
        assert(!buffer_cleanup(&t) && !t.execution.prepared && !t.fs.buffer.pool);
        assert(!md_buffers_take(pool,&held));
        assert(!dispatch_input(&t,0) && !t.execution.prepared && !t.fs.buffer.pool);
        assert(!md_buffer_release(&held));
    }
    t.execution.request.nr=SYS_ppoll;
    t.execution.request.args[1]=md_process_native_nofile(t.resources.process)+1;
    assert(!dispatch_input(&t,0) && !t.fs.buffer.pool);
    t.execution.request.nr=SYS_pselect6; t.execution.request.args[0]=ULONG_MAX;
    assert(!dispatch_input(&t,0) && !t.fs.buffer.pool);
    t.execution.request.args[0]=INT_MAX;
    assert(!dispatch_input(&t,0) && !t.fs.buffer.pool);
    t.execution.request.nr=SYS_write;
    assert(!dispatch_input(&t,0) && !t.fs.buffer.pool);
    md_process_member_release(t.resources.process);
    md_identity_release(&identity); md_memory_release(t.memory);
    puts("PASS prepared input: recv/poll/exec admission, native limits, unmapped and exhausted pool, early cleanup");
}
static void readiness_transport_pressure(void) {
    struct readiness_owner *owner=calloc(1,sizeof(*owner)); assert(owner);
    struct thread t={.memory=md_memory_new(),.readiness=owner};
    assert(t.memory && !md_memory_buffers_prepare(t.memory,1,sizeof(struct md_epoll_frame),0,0));
    struct md_interception_buffers *pool=md_memory_buffers(t.memory);
    assert(!md_memory_buffers_mapped(t.memory,0x100000,md_buffers_size(pool)));
    owner->detached=calloc(1,sizeof(*owner->detached)); assert(owner->detached);
    owner->detached->events[0].data.u64=73;
    owner->detached->timeout.tv_sec=19;
    struct md_interception_buffer held={0};
    assert(!md_buffers_take(pool,&held));
    assert(!readiness_restore(&t,READINESS_RESTORE_REPLY,1));
    assert(owner->restoring==READINESS_RESTORE_REPLY && owner->restore_result==1);
    assert(t.retries[1].mask==RETRY_READINESS && !t.fs.buffer.pool && owner->detached);
    struct md_buffer_span span;
    assert(!readiness_frame(&t,&span) && span.data==owner->detached);
    assert(!md_buffer_release(&held));
    retry_clear(&t,RETRY_READINESS);
    assert(readiness_restore(&t,READINESS_RESTORE_REPLY,1));
    assert(!owner->detached && !owner->restoring && t.fs.buffer.pool);
    assert(!readiness_frame(&t,&span));
    struct md_epoll_frame *frame=span.data;
    assert(frame->events[0].data.u64==73 && frame->timeout.tv_sec==19);
    assert(!md_buffer_release(&t.fs.buffer));
    free(owner); md_memory_release(t.memory);
    puts("PASS readiness transport: exact detached result survives capacity wait and reattachment");
}
static void readiness_publication_eviction(void) {
    fd_ready=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK); assert(fd_ready>=0);
    for (int completed=0;completed<2;++completed) for (int queued=0;queued<2;++queued) {
        struct md_control_epoll *q=md_control_epoll_new(); assert(q);
        struct readiness_owner owner={.queue=md_control_epoll_object(q),.evicting=1,
            .delivery_claimed=1,.delivery_waiting=queued,.loans={{.number=-1},{.number=9}},
            .wait={.task_sampled=3,.task_count=completed}};
        struct thread t={.memory=md_memory_new(),.readiness=&owner}; assert(t.memory);
        struct md_resource_order other={0},next={0};
        assert(md_control_epoll_task_delivery_begin(q)==1);
        if (queued) {
            assert(md_memory_order_begin(t.memory,&other,NULL)==1);
            assert(!md_resource_order_start(&other));
        }
        assert(md_memory_order_begin(t.memory,&owner.publication_memory,NULL)>=0);
        if (!queued) assert(!md_resource_order_start(&owner.publication_memory));
        assert(md_memory_order_begin(t.memory,&next,NULL)==0);
        readiness_evict_park(&t,0,0);
        assert(owner.parked && !owner.delivery_claimed && !owner.delivery_waiting);
        assert(!owner.publication_memory.queue && t.phase==DISPATCHING);
        assert(owner.finishing==completed);
        if (completed) assert(owner.finish_result==1);
        else assert(!owner.wait.task_sampled && !owner.wait.task_count);
        if (queued) assert(!md_resource_order_complete(&other));
        assert(!md_resource_order_start(&next) && !md_resource_order_complete(&next));
        assert(md_control_epoll_task_delivery_begin(q)==1);
        md_control_epoll_task_delivery_finish(q,0);
        md_control_epoll_release(q); md_memory_release(t.memory);
    }
    close(fd_ready); fd_ready=-1;
    puts("PASS readiness publication: eviction releases queued/running memory turns and preserves committed output");
}
static void image_entry_identity(void) {
    tasks=md_task_registry_create(); assert(tasks);
    for (unsigned mode=0;mode<6;++mode) {
        unsigned secure=mode&1, machine=mode>=2;
        struct thread t={.pid=getpid(),.tgid=getpid(),.ready=1,.image.mapped=1,
            .identity=md_identity_new(1000,1234),.image.backings={-1,-1},.memory=md_memory_new()};
        assert(t.memory && !md_resources_init(&t.resources,NULL));
        t.image.pending_identity=(struct md_exec_identity){.value=t.identity,.secure=secure};
        if (secure) t.image.pending_identity.value.uid.effective=0;
        Elf64_auxv_t aux[]={{.a_type=AT_UID},{.a_type=AT_EUID},{.a_type=AT_GID},
            {.a_type=AT_EGID},{.a_type=AT_SECURE},{0}};
        struct md_process_image image={.auxv_bytes=sizeof(aux)};
        assert(image_enter(&t,(uintptr_t)&image,(uintptr_t)aux,129,machine)==-EPERM && !t.entered);
        assert(image_enter(&t,(uintptr_t)&image,1,5,machine)==-EFAULT && !t.entered);
        assert(image_enter(&t,(uintptr_t)&image,(uintptr_t)aux,4,machine)==-EPROTO && !t.entered);
        assert(image_enter(&t,(uintptr_t)&image,(uintptr_t)aux,5,2)==-EPERM && !t.entered);
        int retained=memfd_create("exec-backing",MFD_CLOEXEC); assert(retained>=0);
        size_t page=(size_t)getpagesize(); assert(!ftruncate(retained,page));
        void *mapping=mode>=4 ? MAP_FAILED : mmap(NULL,page,PROT_READ,MAP_PRIVATE,retained,0);
        assert(mode>=4 || mapping!=MAP_FAILED);
        t.image.backings[0]=retained;
        assert(!image_enter(&t,(uintptr_t)&image,(uintptr_t)aux,5,machine));
        assert(md_process_dumpable(t.resources.access)==!secure);
        assert(t.entered && !t.image.mapped && t.image.backings[0]==-1);
        assert(t.image.backings_ready==(machine && mode<4));
        int backing=-1; uint64_t offset=1;
        assert(md_memory_file_view(t.memory,(uintptr_t)mapping,page,&backing,&offset)==t.image.backings_ready);
        if (t.image.backings_ready) assert(backing>=0 && !offset && fcntl(backing,F_GETFD)>=0);
        assert(fcntl(retained,F_GETFD)<0 && errno==EBADF);
        assert(aux[0].a_un.a_val==1000 && aux[1].a_un.a_val==(secure ? 0 : 1000));
        assert(aux[2].a_un.a_val==1234 && aux[3].a_un.a_val==1234 && aux[4].a_un.a_val==secure);
        assert(!memcmp(aux,image.auxv,sizeof(aux)));
        assert(image_enter(&t,(uintptr_t)&image,(uintptr_t)aux,5,machine)==-EPERM);
        md_task_registry_forget(tasks,t.pid);
        if (mapping!=MAP_FAILED) assert(!munmap(mapping,page));
        md_identity_release(&t.identity); md_memory_release(t.memory); assert(!md_resources_release(&t.resources));
    }
    md_task_registry_destroy(tasks); tasks=NULL;
    puts("PASS image entry: exact identity commit, live/snapshot auxv, secure continuation, invalid input, retained mappings and optional backing failure");
}
static void captured_writes(void) {
    struct thread t={.pid=getpid(),.memory=md_memory_new()};
    assert(t.memory && !md_memory_buffers_prepare(t.memory,1,sizeof(struct md_fs_buffer_frame),0,0));
    const struct md_control_file_ops ops={.retain=completion_retain,.release=completion_release,
        .access=completion_access,.write=completion_write};
    t.proc.file=md_proc_file_open_control(&ops,&t,&(struct md_control_opener){.identity=&t.identity},O_WRONLY); assert(t.proc.file);
    char bytes[MD_CONTROL_IO_MAX]; memset(bytes,'x',sizeof(bytes)); memcpy(bytes,"data",4);
    size_t total;
    for (unsigned vector=0;vector<2;++vector) {
        struct iovec iov[3]={{(void *)1,0},{bytes,2},{bytes+2,2}};
        t.execution.request.nr=vector ? SYS_writev : SYS_write;
        t.execution.request.args[1]=(uintptr_t)(vector ? (void *)iov : bytes);
        t.execution.request.args[2]=vector ? 3 : 4;
        assert(proc_write_capture(&t,&total) && total==4);
        struct md_buffer_span span; assert(!md_buffer_span(&t.fs.buffer,&span));
        assert(!memcmp(span.data,"data",4) && !t.proc.io.file && !t.proc.io.turn.queue);
        assert(!md_buffer_release(&t.fs.buffer));
        struct md_interception_buffer held={0};
        assert(!md_buffers_take(md_memory_buffers(t.memory),&held));
        assert(!proc_write_capture(&t,&total) && !t.fs.buffer.pool && !t.proc.io.file);
        assert(!md_buffer_release(&held));
    }
    struct iovec iov[3]={{bytes,sizeof(bytes)},{(void *)1,10}};
    t.execution.request.args[1]=(uintptr_t)iov; t.execution.request.args[2]=2;
    /* Only the prefix accepted by control IO is copied; untouched trailing
     * vectors cannot cause a fault or change the amount offered to the OFD. */
    assert(proc_write_capture(&t,&total) && total==sizeof(bytes)+10);
    struct md_buffer_span span; assert(!md_buffer_span(&t.fs.buffer,&span));
    assert(!memcmp(span.data,bytes,sizeof(bytes)) && !md_buffer_release(&t.fs.buffer));
    for (unsigned mode=0;mode<6;++mode) {
        iov[0]=(struct iovec){bytes,2}; iov[1]=(struct iovec){bytes+2,2};
        t.execution.request.args[1]=(uintptr_t)iov; t.execution.request.args[2]=2;
        if (mode==0) iov[1].iov_base=(void *)1;
        if (mode==1) t.execution.request.args[1]=1;
        if (mode==2) t.execution.request.args[2]=MD_PROC_IO_VECTORS+1;
        if (mode==3) iov[0].iov_len=PTRDIFF_MAX;
        if (mode==4) iov[0].iov_len=iov[1].iov_len=0;
        if (mode==5) t.debug.call_active=1;
        assert(!proc_write_capture(&t,&total) && !t.fs.buffer.pool && !t.proc.io.file);
        t.debug.call_active=0;
    }
    size_t page=(size_t)getpagesize();
    char *mapping=mmap(NULL,page*2,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(mapping!=MAP_FAILED && !mprotect(mapping+page,page,PROT_NONE));
    t.execution.request.nr=SYS_write; t.execution.request.args[1]=(uintptr_t)(mapping+page-2); t.execution.request.args[2]=4;
    assert(!proc_write_capture(&t,&total) && !t.fs.buffer.pool && !t.proc.io.file);
    assert(!munmap(mapping,page*2));
    int ready[2]; assert(!pipe(ready));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(ready[0]); assert(!prctl(PR_SET_DUMPABLE,0,0,0,0));
        assert(write(ready[1],"x",1)==1); for (;;) pause();
    }
    close(ready[1]); char value; assert(read(ready[0],&value,1)==1); close(ready[0]);
    t.pid=child; t.execution.request.args[1]=(uintptr_t)bytes;
    assert(!proc_write_capture(&t,&total) && !t.fs.buffer.pool && !t.proc.io.file);
    assert(!kill(child,SIGKILL)); int status; assert(waitpid(child,&status,0)==child && WIFSIGNALED(status));
    md_proc_file_release(t.proc.file); md_memory_release(t.memory);
    puts("PASS write capture: scalar/vector prefix, empty vector, overflow, partial fault, protected caller and pressure without IO effects");
}
static void repair_events(void) {
    int previous=fd_ready;
    fd_ready=eventfd(0,EFD_NONBLOCK|EFD_CLOEXEC); assert(fd_ready>=0);
    struct thread caller={.pid=123,.phase=OBSERVING,.memory_op.action=MEMORY_KERNEL};
    caller.execution.request.nr=SYS_mprotect;
    thread_index[caller.pid%THREAD_BUCKETS]=&caller;
    struct thread actor={.pid=456};
    struct mapping_owner mapping={.caller=actor.pid};
    struct task_action action={.actor=&actor};
    struct memory_execution memory={.actor=&actor};
    struct retained_memory robust={.creator=&actor};
    struct retained_retirement retirement={.actor=&actor};
    int status=(SIGTRAP|0x80)<<8|0x7f;
    struct rusage usage={.ru_stime.tv_usec=789};
    assert(!quiescence_repair_event(caller.pid,status,&usage));
    for (unsigned mode=1;mode<9;++mode) {
        struct md_task_quiesce **barrier;
        if (mode==1) { memory_execution=&memory; barrier=&memory_barrier; }
        else if (mode==2) { task_action_execution=&action; barrier=&task_action_barrier; }
        else if (mode<=4) {
            mapping_execution=&mapping; barrier=&mapping_barrier;
            if (mode==4) task_action_execution=&action;
        } else if (mode==5) { retained_creation=&robust; barrier=&retained_barrier; }
        else if (mode==6) { retained_retirement=&retirement; barrier=&retained_barrier; }
        else if (mode==7) { policy_installer=&actor; barrier=&policy_barrier; }
        else { task_action_releasing=1; barrier=&task_action_barrier; }
        *barrier=md_task_quiesce_new(no_trace); assert(*barrier);
        caller.execution.request.nr=SYS_mmap;
        assert(!quiescence_repair_event(caller.pid,status,&usage));
        caller.execution.request.nr=SYS_mprotect;
        assert(!quiescence_repair_event(caller.pid,(SIGUSR1<<8)|0x7f,&usage));
        assert(!quiescence_repair_event(caller.pid,(PTRACE_EVENT_EXIT<<16)|(SIGTRAP<<8)|0x7f,&usage));
        memory.actor=&caller; action.actor=&caller; mapping.caller=caller.pid;
        robust.creator=&caller; retirement.actor=&caller;
        if (mode==7) policy_installer=&caller;
        if (mode!=8) assert(!quiescence_repair_event(caller.pid,status,&usage));
        memory.actor=&actor; action.actor=&actor; mapping.caller=actor.pid;
        robust.creator=&actor; retirement.actor=&actor;
        if (mode==7) policy_installer=&actor;
        assert(quiescence_repair_event(caller.pid,status,&usage));
        assert(md_task_quiesce_contains(*barrier,caller.pid));
        assert(!md_task_quiesce_contains(*barrier,actor.pid));
        assert(md_task_quiesce_ready(*barrier));
        if (mode==5 || mode==6) assert(!retained_take(NULL,NULL));
        assert(!md_task_quiesce_release(*barrier));
        int replay=0; struct rusage replay_usage={0};
        assert(md_task_quiesce_take(*barrier,&replay,&replay_usage)==caller.pid);
        assert(replay==status && replay_usage.ru_stime.tv_usec==789);
        assert(!md_task_quiesce_take(*barrier,&replay,&replay_usage));
        md_task_quiesce_destroy(*barrier); *barrier=NULL;
        entry_fence_stage=0; memory_execution=NULL; task_action_execution=NULL; mapping_execution=NULL;
        retained_creation=NULL; retained_retirement=NULL; policy_installer=NULL; task_action_releasing=0;
    }
    for (unsigned mode=0;mode<2;++mode) {
        retirement.stage=1;
        if (mode) retained_retirement=&retirement; else retained_creation=&robust;
        assert(quiescence_repair_event(caller.pid,status,&usage));
        int terminal=(PTRACE_EVENT_EXIT<<16)|(SIGTRAP<<8)|0x7f;
        assert(retained_barrier_event(caller.pid,terminal,&usage));
        assert(!md_task_quiesce_release(retained_barrier));
        retained_creation=NULL; retained_retirement=NULL;
        int replay=0; struct rusage replay_usage={0};
        assert(retained_take(&replay,&replay_usage)==caller.pid);
        assert(replay==terminal && replay_usage.ru_stime.tv_usec==789);
        assert(!retained_take(&replay,&replay_usage) && !retained_barrier);
    }
    thread_index[caller.pid%THREAD_BUCKETS]=NULL;
    close(fd_ready); fd_ready=previous;
    puts("PASS supervisor repair deferral: every owner, nested mapping, exact replay and unrelated progress");
}
static void retirement_preparation_terminal(void) {
    int previous=fd_ready;
    fd_ready=eventfd(0,EFD_NONBLOCK|EFD_CLOEXEC); assert(fd_ready>=0);
    const int terminal[]={
        (PTRACE_EVENT_EXIT<<16)|(SIGTRAP<<8)|0x7f,
        (PTRACE_EVENT_EXEC<<16)|(SIGTRAP<<8)|0x7f,
        SIGKILL,7<<8
    };
    for (unsigned own=0;own<2;++own) for (unsigned i=0;i<sizeof(terminal)/sizeof(*terminal);++i) {
        struct thread actor={.pid=123};
        struct retained_memory memory={.retiring=1};
        struct retained_retirement *retirement=calloc(1,sizeof(*retirement)); assert(retirement);
        *retirement=(struct retained_retirement){.actor=&actor,.memory=&memory};
        retained_retirement=retirement;
        retained_barrier=md_task_quiesce_new(no_trace); assert(retained_barrier);
        assert(!md_task_quiesce_add(retained_barrier,actor.pid,1,0));
        /* The exec leader cannot acknowledge INTERRUPT until its sibling exits. */
        assert(!md_task_quiesce_add(retained_barrier,456,0,0));
        assert(!md_task_quiesce_add(retained_barrier,457,1,0));
        assert(!md_task_quiesce_ready(retained_barrier));
        struct rusage usage={.ru_stime.tv_usec=789};
        assert(!retained_barrier_event(999,terminal[i],&usage) && retained_retirement==retirement);
        pid_t subject=own ? actor.pid : 457;
        assert(retained_barrier_event(subject,terminal[i],&usage)==1 && !retained_retirement);
        assert(!memory.killed && !memory.dead && md_task_quiesce_ready(retained_barrier));
        int status=0; struct rusage replay={0};
        assert(retained_take(&status,&replay)==subject);
        assert(status==terminal[i] && replay.ru_stime.tv_usec==789);
        assert(!retained_take(&status,&replay) && !retained_barrier);
    }
    close(fd_ready); fd_ready=previous;
    puts("PASS private-child retirement: pre-kill barrier yields parent/peer exit and exec, exact replay without waiting for de_thread");
}
static void unused_retirement(void *context,int error) { (void)context; (void)error; assert(0); }
static void repair_preparation_terminal(void) {
    int previous=fd_ready;
    fd_ready=eventfd(0,EFD_NONBLOCK|EFD_CLOEXEC); assert(fd_ready>=0);
    const int terminal[]={
        (PTRACE_EVENT_EXIT<<16)|(SIGTRAP<<8)|0x7f,
        (PTRACE_EVENT_EXEC<<16)|(SIGTRAP<<8)|0x7f,
        SIGKILL,7<<8
    };
    for (unsigned own=0;own<2;++own)
      for (unsigned stage=MEMORY_WRITERS;stage<MEMORY_MAP;++stage)
        for (unsigned i=0;i<sizeof(terminal)/sizeof(*terminal);++i) {
            struct thread actor={.pid=123,.phase=OBSERVING,.memory_op.action=MEMORY_KERNEL};
            struct memory_execution *memory=calloc(1,sizeof(*memory)); assert(memory);
            *memory=(struct memory_execution){.actor=&actor,.stage=stage,
                .status=(SIGTRAP|0x80)<<8|0x7f,.usage={.ru_stime.tv_usec=456}};
            memory_execution=memory;
            memory_barrier=md_task_quiesce_new(no_trace); assert(memory_barrier);
            assert(!md_task_quiesce_add(memory_barrier,actor.pid,1,0));
            assert(!md_task_quiesce_add(memory_barrier,456,0,0));
            assert(!md_task_quiesce_add(memory_barrier,457,1,0));
            struct rusage usage={.ru_stime.tv_usec=789};
            pid_t subject=own ? actor.pid : 457;
            assert(memory_event(subject,terminal[i],&usage)==1 && !memory_execution);
            assert(actor.phase==OBSERVING && actor.memory_op.action==MEMORY_KERNEL);
            assert(!quiescence_busy(NULL));
            int status=0; struct rusage replay={0};
            assert(md_task_quiesce_take(memory_barrier,&status,&replay)==subject);
            assert(status==terminal[i] && replay.ru_stime.tv_usec==789);
            if (!own) {
                assert(md_task_quiesce_take(memory_barrier,&status,&replay)==actor.pid);
                assert(status==((SIGTRAP|0x80)<<8|0x7f) && replay.ru_stime.tv_usec==456);
            }
            assert(!md_task_quiesce_take(memory_barrier,&status,&replay));
            md_task_quiesce_destroy(memory_barrier); memory_barrier=NULL;
        }
    close(fd_ready); fd_ready=previous;
    puts("PASS memory repair preparation: actor/peer exit, exec and death replay without tracee mutation or de_thread deadlock");
}
static void repair_retirement(void) {
    int previous=fd_ready;
    fd_ready=eventfd(0,EFD_NONBLOCK|EFD_CLOEXEC); assert(fd_ready>=0);
    for (unsigned stage=MEMORY_WRITERS;stage<=MEMORY_WORKER;++stage) {
        struct thread actor={.pid=123,.phase=OBSERVING,.memory_op.action=MEMORY_KERNEL};
        struct task_action retirement={.retirement={.task=456,.complete=unused_retirement}};
        struct memory_execution *memory=calloc(1,sizeof(*memory)); assert(memory);
        *memory=(struct memory_execution){.actor=&actor,.stage=stage,
            .status=(SIGTRAP|0x80)<<8|0x7f,.usage={.ru_stime.tv_usec=789}};
        memory_execution=memory; loan_retirements=&retirement;
        memory_barrier=md_task_quiesce_new(no_trace); assert(memory_barrier);
        assert(!md_task_quiesce_add(memory_barrier,actor.pid,1,0));
        assert(!memory_yield_retirement(memory)); /* An unrelated cleanup cannot cancel this repair. */
        assert(!md_task_quiesce_add(memory_barrier,456,stage==MEMORY_WORKER,0));
        memory->stage=MEMORY_VALIDATE; assert(!memory_yield_retirement(memory));
        memory->stage=stage;
        assert(memory_yield_retirement(memory) && !memory_execution);
        assert(loan_retirements==&retirement && actor.phase==OBSERVING && actor.memory_op.action==MEMORY_KERNEL);
        assert(!quiescence_busy(NULL));
        int status=0; struct rusage usage={0};
        assert(md_task_quiesce_take(memory_barrier,&status,&usage)==actor.pid);
        assert(status==((SIGTRAP|0x80)<<8|0x7f) && usage.ru_stime.tv_usec==789);
        assert(!md_task_quiesce_take(memory_barrier,&status,&usage));
        md_task_quiesce_destroy(memory_barrier); memory_barrier=NULL; loan_retirements=NULL;
    }
    close(fd_ready); fd_ready=previous;
    repair_preparation_terminal();
    puts("PASS memory repair yields unmodified mapping to exact FD retirement, retains exit/accounting and never replays mprotect");
}
static void repair_late_terminal(void) {
    const int terminal[]={
        (PTRACE_EVENT_EXIT<<16)|(SIGTRAP<<8)|0x7f,
        (PTRACE_EVENT_EXEC<<16)|(SIGTRAP<<8)|0x7f,
        SIGKILL,7<<8
    };
    for (unsigned stage=MEMORY_MAP;stage<=MEMORY_REPLACE;++stage)
      for (unsigned calling=0;calling<2;++calling)
        for (unsigned i=0;i<sizeof(terminal)/sizeof(*terminal);++i) {
            struct thread caller={.pid=123,.phase=OBSERVING,.memory_op.action=MEMORY_KERNEL};
            struct retained_memory executor={.ready=1,.retained={.actor=789}};
            struct memory_execution memory={.actor=&caller,.executor=&executor,
                .stage=stage,.calling=calling};
            memory_execution=&memory;
            memory_barrier=md_task_quiesce_new(no_trace); assert(memory_barrier);
            assert(!md_task_quiesce_add(memory_barrier,caller.pid,1,0));
            struct rusage usage={.ru_stime.tv_usec=123};
            assert(memory_event(caller.pid,terminal[i],&usage)==1);
            assert(memory_execution==&memory && memory.terminal && memory.error==-ECANCELED);
            assert(memory.calling==(int)calling && memory.replacement.cancelled);
            assert(executor.ready && !executor.retiring && !executor.dead);
            assert(caller.phase==OBSERVING && caller.memory_op.action==MEMORY_KERNEL);
            int status; struct rusage replay;
            assert(!deferred_event(&status,&replay));
            assert(!md_task_quiesce_release(memory_barrier));
            assert(md_task_quiesce_take(memory_barrier,&status,&replay)==caller.pid);
            assert(status==terminal[i] && replay.ru_stime.tv_usec==123);
            assert(!md_task_quiesce_take(memory_barrier,&status,&replay));
            md_task_quiesce_destroy(memory_barrier); memory_barrier=NULL; memory_execution=NULL;
        }
    puts("PASS late memory repair cancellation: caller death/exec retains exact event and independent mm executor through rollback");
}
static unsigned birth_event;
static int birth_terminal;
static long birth_trace(int request,pid_t pid,void *address,void *data) {
    assert(pid==123);
    if (request==PTRACE_GETREGSET) {
        struct iovec *io=data;
        *(struct user_pt_regs *)io->iov_base=(struct user_pt_regs){.regs={[0]=456,[8]=SYS_clone3}};
    } else if (request==PTRACE_GETSIGINFO) {
        assert(!address);
        *(siginfo_t *)data=(siginfo_t){.si_signo=SIGTRAP,
            .si_code=SIGTRAP|((birth_terminal ? PTRACE_EVENT_EXIT : birth_event)<<8)};
    } else {
        assert(request==PTRACE_GETEVENTMSG && !address);
        *(unsigned long *)data=birth_terminal ? SIGKILL : 456;
    }
    return 0;
}
static void quiescent_birth(void) {
    unsigned events[]={PTRACE_EVENT_CLONE,PTRACE_EVENT_FORK,PTRACE_EVENT_VFORK};
    struct thread child={.pid=456,.kernel_stopped=1};
    unsigned bucket=child.pid%THREAD_BUCKETS;
    assert(!thread_index[bucket]); thread_index[bucket]=&child;
    long (*saved_trace)(int,pid_t,void *,void *)=checkpoints.trace;
    checkpoints.trace=birth_trace;
    for (unsigned i=0;i<sizeof(events)/sizeof(*events);++i) for (int terminal=0;terminal<2;++terminal) {
        birth_event=events[i]; birth_terminal=terminal;
        for (int early=0;early<2;++early) {
            child.initial_stop=early;
            struct md_task_quiesce *q=md_task_quiesce_new(birth_trace); assert(q);
            int status=(events[i]<<16)|(SIGTRAP<<8)|0x7f;
            struct rusage usage={.ru_maxrss=17},replayed;
            assert(!md_task_quiesce_add(q,123,1,0));
            assert(!quiesce_event(q,123,status,&usage));
            assert(md_task_quiesce_ready(q)==early && !md_task_quiesce_contains(q,SIGKILL));
            if (!early) assert(!quiesce_event(q,456,(PTRACE_EVENT_STOP<<16)|(SIGTRAP<<8)|0x7f,NULL));
            assert(md_task_quiesce_ready(q) && !md_task_quiesce_release(q));
            int observed;
            assert(md_task_quiesce_take(q,&observed,&replayed)==123 && observed==status && replayed.ru_maxrss==17);
            if (!early) assert(md_task_quiesce_take(q,&observed,NULL)==456);
            assert(!md_task_quiesce_take(q,&observed,NULL));
            md_task_quiesce_destroy(q);
        }
    }
    checkpoints.trace=saved_trace; thread_index[bucket]=NULL;
    puts("PASS quiescent birth: initial child stop predates barrier, every birth event, late stop and exact replay");
}
static unsigned debugger_interrupts, debugger_continuations;
static long debugger_result;
static int debugger_test_group(pid_t pid) { return pid; }
static int debugger_test_parent(pid_t pid) { (void)pid; return 0; }
static int debugger_test_attachable(pid_t caller,pid_t target) { (void)caller; return target==123; }
static int debugger_test_observe(pid_t pid) { (void)pid; return 0; }
static int debugger_test_options(pid_t pid,unsigned long options) { (void)pid; (void)options; return 0; }
static uid_t debugger_test_uid(pid_t pid) { (void)pid; return 0; }
static void debugger_test_usage(pid_t pid,struct rusage *usage) { (void)pid; *usage=(struct rusage){0}; }
static void debugger_test_complete(pid_t pid,long value) {
    (void)pid; debugger_result=value;
}
static long debugger_quiesce_trace(int request,pid_t pid,void *address,void *data) {
    assert(pid==123 && !address && !data);
    if (request==PTRACE_INTERRUPT) ++debugger_interrupts;
    else {
        assert(request==PTRACE_CONT || request==PTRACE_SYSCALL
            || request==PTRACE_SINGLESTEP || request==PTRACE_SYSEMU);
        ++debugger_continuations;
    }
    return 0;
}
static void quiescent_task_ownership(void) {
    const int continuations[]={PTRACE_CONT,PTRACE_SYSCALL,PTRACE_SINGLESTEP,PTRACE_SYSEMU};
    for (unsigned request=0;request<sizeof(continuations)/sizeof(*continuations);++request)
        for (unsigned mode=0;mode<7;++mode) {
            struct thread t={.pid=123,.born=1,.initial_stop=1,.kernel_resume=continuations[request]};
            t.kernel_stopped=mode==1;
            t.listening=mode==2;
            t.process_op.vfork_child=mode==3 ? 456 : 0;
            t.interrupt_pending=mode==5;
            if (mode==6) t.initial_stop=0;
            struct md_task_quiesce *barrier=md_task_quiesce_new(debugger_quiesce_trace); assert(barrier);
            debugger_interrupts=debugger_continuations=0;
            assert(!quiesce_task_add(barrier,&t,mode==4));
            assert(!md_task_quiesce_stop(barrier));
            assert(debugger_interrupts==(mode==0));
            assert(md_task_quiesce_ready(barrier)==(mode>=1 && mode<=4));
            int status=(PTRACE_EVENT_STOP<<16)|(SIGTRAP<<8)|0x7f;
            if (!md_task_quiesce_ready(barrier))
                assert(!md_task_quiesce_event(barrier,t.pid,status,NULL));
            assert(md_task_quiesce_ready(barrier) && !md_task_quiesce_release(barrier));
            assert(debugger_continuations==(mode==0));
            assert(t.kernel_resume==continuations[request]);
            int observed=0;
            if (mode>=5) {
                assert(md_task_quiesce_take(barrier,&observed,NULL)==t.pid && observed==status);
            }
            assert(!md_task_quiesce_take(barrier,&observed,NULL));
            md_task_quiesce_destroy(barrier);
        }
    puts("PASS quiescent task ownership: vfork/listen/held stops, pending birth/interrupt and exact continuation");
}
static void debugger_quiescence(void) {
    const long calls[]={SYS_ptrace,SYS_wait4,SYS_waitid,SYS_read};
    for (unsigned shared=0;shared<2;++shared) for (unsigned i=0;i<sizeof(calls)/sizeof(*calls);++i) {
        struct md_entry_channel channel={0};
        struct thread t={.pid=123,.tgid=123,.born=1,.initial_stop=1,
            .entry.control=&channel,.entry.admission=shared,.execution.request.nr=calls[i]};
        assert(!threads && !thread_index[t.pid%THREAD_BUCKETS]);
        threads=&t; thread_index[t.pid%THREAD_BUCKETS]=&t;
        checkpoints.trace=debugger_quiesce_trace; debugger_interrupts=0;
        assert(!debugger_observe(t.pid));
        assert(debugger_interrupts==(!shared || calls[i]==SYS_wait4 || calls[i]==SYS_waitid));
        threads=NULL; thread_index[t.pid%THREAD_BUCKETS]=NULL;
    }
    const int continuations[]={PTRACE_CONT,PTRACE_SYSCALL,PTRACE_SINGLESTEP,PTRACE_SYSEMU};
    for (unsigned mode=0;mode<sizeof(continuations)/sizeof(*continuations);++mode) {
        struct thread t={.pid=123,.born=1,.initial_stop=1,.entered=1,.image.process_image=1};
        assert(!debugger && !thread_index[t.pid%THREAD_BUCKETS]);
        thread_index[t.pid%THREAD_BUCKETS]=&t;
        checkpoints.trace=debugger_quiesce_trace;
        debugger_interrupts=debugger_continuations=0;
        struct md_task_quiesce *barrier=md_task_quiesce_new(quiesce_trace); assert(barrier);
        assert(!md_task_quiesce_add(barrier,t.pid,0,continuations[mode]));
        assert(!md_task_quiesce_stop(barrier) && debugger_interrupts==1);
        assert(!quiesce_event(barrier,t.pid,(PTRACE_EVENT_STOP<<16)|(SIGTRAP<<8)|0x7f,NULL));
        assert(md_task_quiesce_ready(barrier) && !md_task_quiesce_release(barrier));
        assert(debugger_continuations==1 && !t.memory_op.resume && !t.interrupt_pending);
        assert(t.kernel_resume==continuations[mode]);
        md_task_quiesce_destroy(barrier);
        thread_index[t.pid%THREAD_BUCKETS]=NULL;
    }
    struct md_debugger_host host={.trace=native_trace,.group=debugger_test_group,.parent=debugger_test_parent,
        .attachable=debugger_test_attachable,.observe=debugger_test_observe,
        .options=debugger_test_options,.interrupt=debugger_interrupt,.complete=debugger_test_complete,
        .uid=debugger_test_uid,.usage=debugger_test_usage};
    for (unsigned enabled=0;enabled<2;++enabled) {
        host.seccomp_events=enabled;
        debugger=md_debugger_create(&host); assert(debugger);
        struct user_pt_regs request={.regs={[8]=SYS_ptrace,[0]=PTRACE_SEIZE,
            [1]=123,[3]=PTRACE_O_TRACESECCOMP}};
        assert(md_debugger_call(debugger,getpid(),syscall_description(&request))==MD_DEBUG_CALL_HANDLED);
        assert(debugger_result==(enabled ? 0 : -EIO));
        if (enabled) {
            struct user_pt_regs original={.regs={[8]=SYS_mount,[0]=17,[1]=19},.pc=0x4004,.sp=0x8000};
            assert(md_debugger_seccomp(debugger,123,&original,0x321));
            siginfo_t event={0};
            struct seccomp_data wait={.nr=SYS_waitid,.args={P_PID,123,(uintptr_t)&event,WSTOPPED|WNOWAIT}};
            assert(md_debugger_call(debugger,getpid(),wait)==MD_DEBUG_CALL_HANDLED && !debugger_result);
            assert(event.si_pid==123 && event.si_code==CLD_TRAPPED);
            wait=(struct seccomp_data){.nr=SYS_wait4,.args={123,1,__WALL}};
            assert(md_debugger_call(debugger,getpid(),wait)==MD_DEBUG_CALL_HANDLED && debugger_result==-EFAULT);
            int status=0; wait.args[1]=(uintptr_t)&status;
            assert(md_debugger_call(debugger,getpid(),wait)==MD_DEBUG_CALL_HANDLED && debugger_result==123);
            assert(WIFSTOPPED(status) && (unsigned)status>>16==PTRACE_EVENT_SECCOMP);
            struct ptrace_syscall_info info={0};
            request.regs[0]=PTRACE_GET_SYSCALL_INFO; request.regs[2]=sizeof(info);
            request.regs[3]=(uintptr_t)&info;
            assert(md_debugger_call(debugger,getpid(),syscall_description(&request))==MD_DEBUG_CALL_HANDLED);
            assert(debugger_result==(long)(offsetof(struct ptrace_syscall_info,seccomp.ret_data)+sizeof(info.seccomp.ret_data)));
            assert(info.op==PTRACE_SYSCALL_INFO_SECCOMP && info.seccomp.nr==SYS_mount
                && info.seccomp.ret_data==0x321 && info.seccomp.args[0]==17
                && info.instruction_pointer==original.pc && info.stack_pointer==original.sp);
            unsigned char short_info[8]; memset(short_info,0x5a,sizeof(short_info));
            request.regs[2]=1; request.regs[3]=(uintptr_t)short_info;
            assert(md_debugger_call(debugger,getpid(),syscall_description(&request))==MD_DEBUG_CALL_HANDLED);
            assert(short_info[0]==PTRACE_SYSCALL_INFO_SECCOMP && short_info[1]==0x5a);
            request.regs[0]=PTRACE_SETOPTIONS; request.regs[2]=request.regs[3]=0;
            assert(md_debugger_call(debugger,getpid(),syscall_description(&request))==MD_DEBUG_CALL_HANDLED && !debugger_result);
            assert(!md_debugger_seccomp(debugger,123,&original,0x322));
        }
        md_debugger_destroy(debugger); debugger=NULL;
    }
    host.seccomp_events=0;
    for (unsigned mode=0;mode<sizeof(continuations)/sizeof(*continuations);++mode) for (unsigned seized=0;seized<2;++seized) {
        struct thread t={.pid=123,.born=1,.initial_stop=1,.entered=1,.image.process_image=1};
        assert(!thread_index[t.pid%THREAD_BUCKETS]); thread_index[t.pid%THREAD_BUCKETS]=&t;
        checkpoints.trace=debugger_quiesce_trace;
        debugger_interrupts=debugger_continuations=0;
        debugger=md_debugger_create(&host); assert(debugger);
        struct md_task_quiesce *barrier=md_task_quiesce_new(quiesce_trace); assert(barrier);
        assert(!md_task_quiesce_add(barrier,t.pid,0,continuations[mode]));
        assert(!md_task_quiesce_stop(barrier) && debugger_interrupts==1);
        struct user_pt_regs call={.regs={[8]=SYS_ptrace,[0]=seized ? PTRACE_SEIZE : PTRACE_ATTACH,[1]=t.pid}};
        assert(md_debugger_call(debugger,getpid(),syscall_description(&call)) && !debugger_result);
        if (seized) {
            call.regs[0]=PTRACE_INTERRUPT;
            assert(md_debugger_call(debugger,getpid(),syscall_description(&call)) && !debugger_result);
        }
        assert(debugger_interrupts==2 && t.interrupt_pending);
        assert(md_debugger_stop_pending(debugger,t.pid));
        assert(entry_return_pending(&t));
        /* Both owners receive the same kernel interruption. Barrier release
         * must return to dispatcher admission before any guest continuation. */
        assert(!quiesce_event(barrier,t.pid,(PTRACE_EVENT_STOP<<16)|(SIGTRAP<<8)|0x7f,NULL));
        assert(md_task_quiesce_ready(barrier) && !md_task_quiesce_release(barrier));
        assert(!debugger_continuations && t.memory_op.resume && t.kernel_stopped && !t.interrupt_pending);
        assert(md_debugger_boundary(debugger,t.pid));
        assert(md_debugger_stopped_by(debugger,t.pid,getpid()));
        assert(!md_debugger_stop_pending(debugger,t.pid));
        assert(!entry_return_pending(&t));
        md_task_quiesce_destroy(barrier);
        md_debugger_destroy(debugger); debugger=NULL;
        thread_index[t.pid%THREAD_BUCKETS]=NULL;
    }
    checkpoints.trace=checkpoint_native_trace;
    puts("PASS debugger quiescence: attach/seize share barrier interrupt, all continuations retain pending stop");
}
static long debugger_wait_trace(int request,pid_t pid,void *address,void *data) {
    struct thread *t=find_thread(pid); assert(t && pid==123);
    if (!readiness_interrupted_step && t->phase==EPOLL_WAIT) {
        assert(request==PTRACE_GETREGSET && (uintptr_t)address==NT_PRSTATUS);
        struct iovec *io=data; assert(io->iov_len==sizeof(struct user_pt_regs));
        *(struct user_pt_regs *)io->iov_base=(struct user_pt_regs){.pc=abi.task_return,
            .regs={[0]=(uint64_t)-514,[8]=SYS_ppoll}};
        ++readiness_interrupted_step; return 0;
    }
    return readiness_interrupted_trace(request,pid,address,data);
}
static void debugger_provider_wait(void) {
    struct md_debugger_host host={.trace=native_trace,.group=debugger_test_group,.parent=debugger_test_parent,
        .attachable=debugger_test_attachable,.observe=debugger_test_observe,
        .options=debugger_test_options,.interrupt=debugger_interrupt,.complete=debugger_test_complete,
        .uid=debugger_test_uid,.usage=debugger_test_usage};
    for (unsigned epoll=0;epoll<2;++epoll) for (unsigned seized=0;seized<2;++seized) {
        struct thread t={.pid=123,.phase=epoll ? EPOLL_WAIT : SOCKET_WAITING,.kernel_stopped=1,
            .memory=md_memory_new(),.readiness=calloc(1,sizeof(struct readiness_owner))};
        assert(t.memory && t.readiness);
        t.readiness->detached=calloc(1,sizeof(*t.readiness->detached));
        t.readiness->poll=md_poll_wait_new(0,0);
        assert(t.readiness->detached && t.readiness->poll);
        t.execution.registers=(struct user_pt_regs){.pc=0x1004,.regs={7,0x2000,3,4000,0,8}};
        t.execution.registers.regs[8]=SYS_epoll_pwait;
        execution_capture(&t,&t.execution.registers);
        thread_index[t.pid%THREAD_BUCKETS]=&t;
        checkpoints.trace=debugger_wait_trace; readiness_interrupted_step=0;
        debugger=md_debugger_create(&host); assert(debugger);
        struct seccomp_data call={.nr=SYS_ptrace,.args={seized ? PTRACE_SEIZE : PTRACE_ATTACH,t.pid}};
        assert(md_debugger_call(debugger,getpid(),call) && !debugger_result);
        if (seized) {
            call.args[0]=PTRACE_INTERRUPT;
            assert(md_debugger_call(debugger,getpid(),call) && !debugger_result);
        }
        assert(md_debugger_stop_pending(debugger,t.pid));
        int status=(PTRACE_EVENT_STOP<<16)|(SIGTRAP<<8)|0x7f;
        assert(epoll ? readiness_native_event(&t,status) : interrupted_operation(&t));
        assert(readiness_interrupted_step==3 && t.phase==IDLE && !t.readiness);
        assert(md_debugger_boundary(debugger,t.pid) && md_debugger_stopped_by(debugger,t.pid,getpid()));
        assert(!md_debugger_stop_pending(debugger,t.pid));
        md_debugger_destroy(debugger); debugger=NULL;
        checkpoints.trace=checkpoint_native_trace; thread_index[t.pid%THREAD_BUCKETS]=NULL;
        md_memory_release(t.memory);
    }
    puts("PASS debugger provider wait: attach/seize interrupt poll and epoll without readiness or interrupt loops");
}
static void event_completion(void) {
    int previous=signal_fd,status=0x1234;
    signal_fd=-1;
    assert(!tasks_pending());
    assert(!wait_event(&status,NULL) && status==0x1234);
    signal_fd=previous;
    puts("PASS event completion: last owner drained without an extra kernel wait");
}
static void event_accounting(void) {
    int source[2],previous_signal_fd=signal_fd;
    assert(!pipe2(source,O_NONBLOCK|O_CLOEXEC)); signal_fd=source[0];
    struct rusage usage,zero={0}; int status;
    for (unsigned round=0;round<3;++round) {
        accounting_enabled=round==1;
        memset(&usage,0xa5,sizeof(usage));
        assert(kernel_event(&status,&usage)==wait_result);
        assert(accounting_requested==accounting_enabled);
        if (accounting_enabled) assert(usage.ru_utime.tv_usec==123 && usage.ru_maxrss==456);
        else assert(!memcmp(&usage,&zero,sizeof(usage)));
        struct md_task_quiesce *queue=md_task_quiesce_new(no_trace); assert(queue);
        assert(!md_task_quiesce_event(queue,wait_result,status,&usage));
        accounting_enabled=!accounting_enabled;
        struct rusage replay;
        assert(md_task_quiesce_take(queue,&status,&replay)==wait_result);
        assert(!memcmp(&replay,&usage,sizeof(usage)));
        md_task_quiesce_destroy(queue);
    }
    accounting_enabled=0;
    assert(kernel_event(&status,NULL)==wait_result && !accounting_requested);
    struct signalfd_siginfo hint={.ssi_signo=SIGCHLD,.ssi_pid=456};
    assert(write(source[1],&hint,sizeof(hint))==sizeof(hint));
    task_wait.pending_count=1;
    assert(kernel_event(&status,NULL)==wait_result && !task_wait.count);
    task_wait.pending_count=0;
    assert(kernel_event(&status,NULL)==wait_result && task_wait.count==1
        && task_wait.hints[task_wait.first]==456);
    task_wait=(struct md_task_wait){0};
    close(source[0]); close(source[1]); signal_fd=previous_signal_fd;
    puts("PASS supervisor accounting: absent, present, detach and deferred events survive debugger changes");
}
static volatile sig_atomic_t received;
static void receive_signal(int signal,siginfo_t *info,void *context) {
    (void)context;
    if ((signal!=SIGRTMAX && signal!=SIGUSR1) || info->si_code!=SI_QUEUE
            || info->si_value.sival_int!=77 || ++received!=1) _exit(92);
}
static void completed_open(unsigned mode,int signal) {
    received=0; adoption_calls=0; adoption_error=mode==1 ? -ENOMEM : mode==2 ? -ESTALE : 0;
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        struct sigaction handler={.sa_sigaction=receive_signal,.sa_flags=SA_SIGINFO};
        sigemptyset(&handler.sa_mask); assert(!sigaction(signal,&handler,NULL));
        assert(!ptrace(PTRACE_TRACEME,0,NULL,NULL));
        (void)raise(SIGSTOP);
        while (!received) pause();
        _exit(received!=1);
    }
    int status;
    assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status) && WSTOPSIG(status)==SIGSTOP);
    assert(!ptrace(PTRACE_SETOPTIONS,pid,NULL,(void *)(uintptr_t)PTRACE_O_EXITKILL));
    union sigval value={.sival_int=77}; assert(!sigqueue(pid,signal,value));
    assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
    assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status) && WSTOPSIG(status)==signal);
    struct user_pt_regs saved;
    struct iovec io={&saved,sizeof(saved)};
    assert(!ptrace(PTRACE_GETREGSET,pid,(void *)NT_PRSTATUS,&io));
    struct thread t={.pid=pid,.tgid=pid,.phase=IDLE,.memory=md_memory_new()};
    thread_index[(unsigned)pid%THREAD_BUCKETS]=&t;
    assert(t.memory && !md_memory_buffers_prepare(t.memory,1,4096,0,0));
    struct md_interception_buffer held={0};
    if (!mode) assert(!md_buffers_take(md_memory_buffers(t.memory),&held));
    t.broker_completion=calloc(1,sizeof(*t.broker_completion)); assert(t.broker_completion);
    t.broker_completion->call=(struct seccomp_data){.nr=SYS_openat,.arch=AUDIT_ARCH_AARCH64,
        .instruction_pointer=saved.pc};
    t.broker_completion->opened.fd=open("/dev/null",O_RDONLY|O_CLOEXEC);
    assert(t.broker_completion->opened.fd>=0);
    struct rusage usage={0};
    assert(completed_notification_event(&t,status,&usage)==(mode==3));
    assert(!t.broker_completion && adoption_calls==(mode!=0));
    if (mode!=3) {
        assert(!t.broker_event.held && !t.fs.buffer.pool && !t.fs.call);
        assert(!md_signal_delivery_pending(&t.signals.delivery) && !t.signals.retained);
        struct user_pt_regs result;
        io=(struct iovec){&result,sizeof(result)};
        assert(!ptrace(PTRACE_GETREGSET,pid,(void *)NT_PRSTATUS,&io));
        assert((long)result.regs[0]==(!mode ? -EAGAIN : adoption_error));
        assert(!ptrace(PTRACE_CONT,pid,NULL,(void *)(intptr_t)signal));
    } else {
        assert(t.broker_event.held && t.fs.buffer.pool && t.fs.call);
        assert(md_signal_delivery_pending(&t.signals.delivery));
        /* Simulate worker completion at an internal checkpoint. The old
         * delivery stop is suppressed; only the queue publishes the signal. */
        assert(!md_signal_delivery_publish(&t.signals.delivery,pid,pid));
        assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
        assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status) && WSTOPSIG(status)==signal);
        int deliver=signal;
        assert(md_signal_delivery_event(&t.signals.delivery,pid,signal,&deliver)>=0);
        assert(deliver==signal && !md_signal_delivery_pending(&t.signals.delivery));
        assert(!ptrace(PTRACE_CONT,pid,NULL,(void *)(intptr_t)deliver));
        md_fs_buffer_open_release(&adopted); t.fs.call=NULL;
    }
    assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!md_buffer_release(&held) && !md_buffer_release(&t.fs.buffer));
    md_signal_delivery_clear(&t.signals.delivery); md_memory_release(t.memory);
    thread_index[(unsigned)pid%THREAD_BUCKETS]=NULL;
}
extern void test_wait_svc(void);
__asm__(".text\n.balign 4\ntest_wait_svc:\nsvc #0\nret\n");
static void provider_wait_restarts(void) {
    struct thread t={.execution.request={.nr=SYS_read}};
    assert(control_wait_restart(&t)==RESTART_SIGNAL);
    t.proc.io.executed=1;
    assert(!control_wait_restart(&t));
    struct md_control_pending pending={.retry_preparation=1};
    for (unsigned io=0;io<2;++io) {
        t.control.pending=io ? NULL : &pending;
        t.proc.io.pending=io ? &pending : NULL;
        assert(control_wait_restart(&t)==RESTART_PREPARATION);
        pending.retry_preparation=0;
        assert(!control_wait_restart(&t));
        pending.retry_preparation=1;
        t.proc.io.transferred=1;
        assert(!control_wait_restart(&t));
        t.proc.io.transferred=0;
        t.execution.request.nr=SYS_splice;
        assert(!control_wait_restart(&t));
        t.execution.request.nr=SYS_read;
        assert(control_wait_restart(&t)==RESTART_PREPARATION);
    }
    puts("PASS provider wait restart: direct/IO preparation, committed effects, partial data and splice");
}
static void provider_wait_boundaries(void) {
    const enum phase phases[]={FILE_WAIT_ENTRY,CONTROL_WAIT_ENTRY,CLOSE_WAIT_ENTRY,DEBUG_PARK_ENTRY};
    uintptr_t saved_call=abi.task_call,saved_return=abi.task_return;
    abi.task_call=(uintptr_t)test_wait_svc;
    abi.task_return=abi.task_call+4;
    for (unsigned p=0;p<sizeof(phases)/sizeof(*phases);++p) for (unsigned pending_exit=0;pending_exit<2;++pending_exit) {
        pid_t pid=fork(); assert(pid>=0);
        if (!pid) {
            pid_t expected=getpid();
            assert(!ptrace(PTRACE_TRACEME,0,NULL,NULL));
            raise(SIGSTOP);
            struct sock_filter code[]={
                BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
                BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getpid,0,1),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
            };
            struct sock_fprog filter={sizeof(code)/sizeof(*code),code};
            assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
            assert(!prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&filter));
            _exit(syscall(SYS_getpid)!=expected);
        }
        int status;
        assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status));
        assert(!ptrace(PTRACE_SETOPTIONS,pid,NULL,(void *)(uintptr_t)
            (PTRACE_O_EXITKILL|PTRACE_O_TRACESYSGOOD|PTRACE_O_TRACESECCOMP)));
        assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
        assert(waitpid(pid,&status,0)==pid && (unsigned)status>>16==PTRACE_EVENT_SECCOMP);
        struct thread t={.pid=pid,.phase=phases[p],.signals.host=md_app_uid_active};
        assert(!thread_index[(unsigned)pid%THREAD_BUCKETS]);
        thread_index[(unsigned)pid%THREAD_BUCKETS]=&t;
        assert(registers(pid,&t.execution.registers) && skip(pid));
        t.fs.registers=t.close.registers=t.execution.registers;
        if (!pending_exit) {
            assert(!ptrace(PTRACE_SYSCALL,pid,NULL,NULL));
            assert(waitpid(pid,&status,0)==pid && WSTOPSIG(status)==(SIGTRAP|0x80));
        }
        struct user_pt_regs poll=wait_poll_registers(&t,t.phase);
        assert(set_registers(pid,&poll));
        assert(!ptrace(PTRACE_SYSCALL,pid,NULL,NULL));
        assert(waitpid(pid,&status,0)==pid && WSTOPSIG(status)==(SIGTRAP|0x80));
        if (pending_exit) {
            assert(!wait_poll_entry(&t) && t.phase==phases[p]);
            assert(!ptrace(PTRACE_SYSCALL,pid,NULL,NULL));
            assert(waitpid(pid,&status,0)==pid && WSTOPSIG(status)==(SIGTRAP|0x80));
        }
        assert(wait_poll_entry(&t)==1);
        struct user_pt_regs entered;
        assert(registers(pid,&entered));
        for (unsigned i=0;i<5;++i) assert(!entered.regs[i]);
        /* Provider completion may precede entry. Cancel only the actual poll,
         * drain its exit, then publish the application's retained result. */
        enum phase completed=phases[p]==DEBUG_PARK_ENTRY ? DEBUG_PARK_EXIT : FILE_WAITING;
        wait_cancel_entry(&t,completed);
        assert(t.phase==WAIT_CANCEL_EXIT && t.execution.wait_resume==completed);
        assert(waitpid(pid,&status,0)==pid && WSTOPSIG(status)==(SIGTRAP|0x80));
        t.execution.registers.regs[0]=pid;
        assert(set_registers(pid,&t.execution.registers));
        assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
        assert(waitpid(pid,&status,0)==pid);
        if (!WIFEXITED(status) || WEXITSTATUS(status))
            fprintf(stderr,"provider wait phase=%d pending_exit=%u status=%#x\n",phases[p],pending_exit,status);
        assert(WIFEXITED(status) && !WEXITSTATUS(status));
        thread_index[(unsigned)pid%THREAD_BUCKETS]=NULL;
    }
    abi.task_call=saved_call; abi.task_return=saved_return;
    puts("PASS provider/debugger wait: seccomp exit drain, post-exit entry, poll arguments and cancelled completion");
}
static void debugger_wait_cancellation(void) {
    struct md_debugger_host host={.wait_children=wait_test_children,.complete=wait_test_complete,.wait=wait_test_run};
    assert(!debugger);
    debugger=md_debugger_create(&host); assert(debugger);
    struct seccomp_data call={.nr=SYS_wait4,.args={(uint64_t)-1,0,__WALL}};
    wait_test_hidden=1; wait_test_last=1; wait_test_done=0;
    assert(md_debugger_call(debugger,123,call));
    md_debugger_wait_complete(debugger,123,0,NULL);
    assert(!wait_test_done && wait_test_action==MD_DEBUG_WAIT_PARK);
    struct thread t={.pid=123,.phase=WAIT_CANCEL_EXIT,.kernel_stopped=1,
        .execution.wait_resume=DEBUG_PARK_EXIT,.debug.wait_waking=1,
        .debug.wait_mask=UINT64_C(1)<<(SIGUSR1-1),.signals.shielded=1};
    continuation_registers=(struct user_pt_regs){.regs={[0]=(uint64_t)-EPERM}};
    continuation_mask=UINT64_MAX;
    checkpoints.trace=continuation_trace;
    execution_syscall_event(&t,123,((SIGTRAP|0x80)<<8)|0x7f,NULL);
    assert(t.phase==IDLE && !t.debug.wait_waking && !t.signals.shielded);
    assert(continuation_mask==t.debug.wait_mask && !wait_test_done);
    assert(wait_test_action==MD_DEBUG_WAIT_REPROBE && wait_test_probe.args[0]==1);
    md_debugger_wait_complete(debugger,123,1,NULL);
    assert(wait_test_done==1 && wait_test_result==1);
    md_debugger_destroy(debugger); debugger=NULL;
    checkpoints.trace=checkpoint_native_trace;
    puts("PASS debugger wait cancellation: exact acknowledgement ignores carrier result and reprobes once");
}
static void checkpoint_admission(void) {
    struct thread t={.pid=123,.memory=md_memory_new()}; assert(t.memory);
    assert(checkpoint_restricted(t.pid));
    thread_index[t.pid%THREAD_BUCKETS]=&t;
    assert(!checkpoint_restricted(t.pid));
    t.seccomp.application_filter=1; assert(checkpoint_restricted(t.pid));
    struct md_svc_registry *r=md_svc_registry_new(0x1000000,0x4000,0x5000); assert(r);
    assert(!md_memory_svc_set(t.memory,r) && checkpoint_restricted(t.pid));
    struct sock_filter allow[]={BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
    struct sock_fprog program={1,allow};
    struct md_seccomp_policy *parent=NULL,*child=NULL,*grandchild=NULL;
    assert(!md_seccomp_policy_capture(NULL,&program,&parent));
    t.seccomp.policy=parent; assert(checkpoint_restricted(t.pid));
    struct md_seccomp_gate gates[FILTER_SERVICE_GATES];
    filter_service_gates(gates);
    assert(!md_seccomp_policy_bind_entry(parent,r->image,gates,FILTER_SERVICE_GATES));
    for (unsigned host=0;host<2;++host) {
        t.signals.host=host; assert(!checkpoint_restricted(t.pid));
    }
    assert(!md_seccomp_policy_capture(parent,&program,&child));
    t.seccomp.policy=child; assert(checkpoint_restricted(t.pid));
    assert(!md_seccomp_policy_bind_entry(child,r->image+1,gates,FILTER_SERVICE_GATES));
    assert(!checkpoint_restricted(t.pid));
    assert(!md_seccomp_policy_capture(child,&program,&grandchild));
    ++gates[0].first;
    assert(!md_seccomp_policy_bind_entry(grandchild,r->image,gates,FILTER_SERVICE_GATES));
    t.seccomp.policy=grandchild;
    assert(md_seccomp_policy_entry_compatible(t.seccomp.policy,r->image) && checkpoint_restricted(t.pid));
    md_seccomp_policy_release(grandchild); md_seccomp_policy_release(child); md_seccomp_policy_release(parent);
    md_memory_release(t.memory); thread_index[t.pid%THREAD_BUCKETS]=NULL;
    puts("PASS checkpoint admission: stable inherited gates avoid emulation; changed and unbound gates retain it");
}
static unsigned checkpoint_trace_calls,checkpoint_op,checkpoint_broker_resumes;
static int checkpoint_broker_observation;
void __real_md_broker_resumed(struct md_namespace_broker *,pid_t);
void __wrap_md_broker_resumed(struct md_namespace_broker *owner,pid_t pid) {
    if (checkpoint_broker_observation) {
        assert(pid==123); ++checkpoint_broker_resumes;
    }
    __real_md_broker_resumed(owner,pid);
}
static long checkpoint_barrier_trace(int request,pid_t pid,void *address,void *data) {
    assert(pid==123); ++checkpoint_trace_calls;
    if (request==PTRACE_GET_SYSCALL_INFO) {
        struct ptrace_syscall_info *info=data;
        *info=(struct ptrace_syscall_info){.op=checkpoint_op,.instruction_pointer=0x1204};
    } else if (request==PTRACE_GETREGSET) {
        assert((uintptr_t)address==NT_PRSTATUS);
        struct iovec *io=data;
        *(struct user_pt_regs *)io->iov_base=md_task_registers_entry(0x1204,0x8000,0);
    } else assert(request==PTRACE_SETREGSET || request==PTRACE_SYSCALL || request==PTRACE_SYSEMU
        || request==PTRACE_INTERRUPT || request==PTRACE_CONT);
    return 0;
}
static void checkpoint_quiescence(void) {
    checkpoint_broker_observation=1;
    struct thread t={.pid=123,.seccomp.application_filter=1};
    thread_index[t.pid%THREAD_BUCKETS]=&t;
    struct md_task_checkpoints saved=checkpoints;
    checkpoints=(struct md_task_checkpoints){.trace=checkpoint_barrier_trace,
        .resumed=checkpoint_resumed,.restricted=checkpoint_restricted,
        .marker=checkpoint_marker,.begin=0x1000,.end=0x2000};
    struct memory_execution memory={0}; struct mapping_owner mapping={0}; struct task_action action={0};
    struct retained_memory robust={0}; struct retained_retirement retirement={0};
    for (unsigned mode=1;mode<7;++mode) for (unsigned stage=0;stage<3;++stage)
            for (unsigned borrowed=0;borrowed<2;++borrowed) {
        struct user_pt_regs r=md_task_registers_entry(0x1200,0x8000,0);
        t.kernel_stopped=1;
        assert(set_registers(t.pid,&r) && !native_trace(PTRACE_CONT,t.pid,NULL,NULL));
        assert(!t.kernel_stopped && t.kernel_resume==PTRACE_SYSEMU);
        int status=(SIGTRAP|0x80)<<8|0x7f;
        for (unsigned i=0;i<stage;++i) {
            checkpoint_op=i==1 ? PTRACE_SYSCALL_INFO_EXIT : PTRACE_SYSCALL_INFO_ENTRY;
            assert(checkpoint_event(t.pid,&status)==1);
            assert(t.kernel_resume==(i==2 ? PTRACE_SYSEMU : PTRACE_SYSCALL));
        }
        checkpoint_op=stage==1 ? PTRACE_SYSCALL_INFO_EXIT : PTRACE_SYSCALL_INFO_ENTRY;
        struct md_task_quiesce **barrier;
        if (mode==1) { memory_execution=&memory; barrier=&memory_barrier; }
        else if (mode==2) { mapping_execution=&mapping; barrier=&mapping_barrier; }
        else if (mode==3) { task_action_execution=&action; barrier=&task_action_barrier; }
        else if (mode==4) { policy_installer=&t; barrier=&policy_barrier; }
        else if (mode==5) { retained_creation=&robust; barrier=&retained_barrier; }
        else { retained_retirement=&retirement; barrier=&retained_barrier; }
        *barrier=md_task_quiesce_new(no_trace); assert(*barrier);
        assert(!md_task_quiesce_add(*barrier,t.pid,borrowed,PTRACE_CONT));
        unsigned before=checkpoint_trace_calls,resumes=checkpoint_broker_resumes;
        t.kernel_stopped=1;
        if (borrowed) {
            struct md_task_quiesce_stop boundary;
            assert(!md_task_quiesce_borrow(*barrier,t.pid,&boundary));
            assert(checkpoint_event(t.pid,&status)==1 && checkpoint_trace_calls>before);
            assert(checkpoint_broker_resumes==resumes+1);
            assert(!t.kernel_stopped);
            assert(t.kernel_resume==(stage==2 ? PTRACE_SYSEMU : PTRACE_SYSCALL));
            assert(!md_task_quiesce_return(*barrier,t.pid));
        } else {
            assert(checkpoint_event(t.pid,&status)==CHECKPOINT_PARKED && checkpoint_trace_calls==before);
            assert(checkpoint_broker_resumes==resumes);
            assert(t.kernel_stopped);
            assert(!md_task_quiesce_event(*barrier,t.pid,status,NULL));
        }
        assert(md_task_quiesce_ready(*barrier) && !md_task_quiesce_release(*barrier));
        entry_fence_stage=0; memory_execution=NULL; mapping_execution=NULL; task_action_execution=NULL;
        policy_installer=NULL; retained_creation=NULL; retained_retirement=NULL;
        if (!borrowed) {
            assert(md_task_quiesce_take(*barrier,&status,NULL)==t.pid);
            assert(checkpoint_event(t.pid,&status)==1 && checkpoint_trace_calls>before);
            assert(checkpoint_broker_resumes==resumes+1);
            assert(!t.kernel_stopped);
            assert(t.kernel_resume==(stage==2 ? PTRACE_SYSEMU : PTRACE_SYSCALL));
        }
        assert(!md_task_quiesce_take(*barrier,&status,NULL));
        md_task_quiesce_destroy(*barrier); *barrier=NULL;
        md_task_checkpoints_clear(&checkpoints);
    }
    for (unsigned stage=0;stage<3;++stage) for (unsigned borrowed=0;borrowed<2;++borrowed) {
        struct user_pt_regs r=md_task_registers_entry(0x1200,0x8000,0);
        assert(set_registers(t.pid,&r) && !native_trace(PTRACE_CONT,t.pid,NULL,NULL));
        int status=(SIGTRAP|0x80)<<8|0x7f;
        for (unsigned i=0;i<stage;++i) {
            checkpoint_op=i==1 ? PTRACE_SYSCALL_INFO_EXIT : PTRACE_SYSCALL_INFO_ENTRY;
            assert(checkpoint_event(t.pid,&status)==1);
        }
        struct md_task_quiesce *barrier=md_task_quiesce_new(quiesce_trace); assert(barrier);
        assert(!quiesce_task_add(barrier,&t,0) && !md_task_quiesce_stop(barrier));
        status=(PTRACE_EVENT_STOP<<16)|(SIGTRAP<<8)|0x7f;
        assert(!md_task_quiesce_event(barrier,t.pid,status,NULL));
        t.kernel_stopped=1;
        if (borrowed) {
            struct md_task_quiesce_stop boundary;
            assert(!md_task_quiesce_borrow(barrier,t.pid,&boundary));
            assert(boundary.kind==MD_TASK_QUIESCE_SYNTHETIC);
            checkpoint_op=PTRACE_SYSCALL_INFO_NONE;
            assert(!md_task_checkpoint_suspend(&checkpoints,t.pid));
            assert(!native_trace(PTRACE_SYSCALL,t.pid,NULL,NULL));
            assert(set_registers(t.pid,&r));
            md_task_checkpoint_restore(&checkpoints,t.pid);
            assert(!md_task_quiesce_return(barrier,t.pid));
            t.kernel_stopped=1;
        }
        assert(!md_task_quiesce_release(barrier) && !t.kernel_stopped);
        assert(t.kernel_resume==(stage==0 ? PTRACE_SYSEMU : PTRACE_SYSCALL));
        status=(SIGTRAP|0x80)<<8|0x7f;
        checkpoint_op=stage==1 ? PTRACE_SYSCALL_INFO_EXIT : PTRACE_SYSCALL_INFO_ENTRY;
        assert(checkpoint_event(t.pid,&status)==1);
        assert(t.kernel_resume==(stage==2 ? PTRACE_SYSEMU : PTRACE_SYSCALL));
        md_task_quiesce_destroy(barrier);
        md_task_checkpoints_clear(&checkpoints);
    }
    checkpoint_broker_observation=0;
    checkpoints=saved; thread_index[t.pid%THREAD_BUCKETS]=NULL;
    puts("PASS checkpoint quiescence: all barriers replay once, resumed channels wake, borrowed helpers progress");
}
static struct seccomp_notif admission_request(void) {
    assert(!md_event_wait_fd(listener,POLLIN,md_event_now()+3000000000LL));
    struct seccomp_notif q={0};
    assert(!ioctl(listener,SECCOMP_IOCTL_NOTIF_RECV,&q));
    return q;
}
static int notification_stop(pid_t pid) {
    int status;
    /* EVENT_WAIT: a real notification cancellation; the suite alarm bounds failure. */
    assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status));
    assert((unsigned)status>>16==PTRACE_EVENT_STOP && WSTOPSIG(status)==SIGTRAP);
    return status;
}
static void notification_rejections(struct thread *t,const struct seccomp_notif *q) {
    assert(!fflush(NULL));
    for (unsigned mismatch=0;mismatch<11;++mismatch) {
        int output[2]; assert(!pipe2(output,O_CLOEXEC));
        pid_t reporter=fork(); assert(reporter>=0);
        if (!reporter) {
            close(output[0]); assert(dup2(output[1],STDERR_FILENO)==STDERR_FILENO); close(output[1]);
            struct md_broker_completion result={.call=q->data,.notification_id=q->id,
                .opened={.fd=-1}};
            t->broker_completion=&result;
            if (mismatch<6) ++result.call.args[mismatch];
            else if (mismatch==6) ++result.call.nr;
            else if (mismatch==7) result.call.arch^=1;
            else if (mismatch==8) result.call.instruction_pointer+=4;
            else if (mismatch==9) t->phase=DISPATCHING;
            else completed_notification(t->pid,&result);
            handle_notification(q);
            _exit(93);
        }
        close(output[1]);
        char report[4096]; size_t size=0; ssize_t count;
        while ((count=read(output[0],report+size,sizeof(report)-1-size))>0) {
            size+=(size_t)count; assert(size<sizeof(report)-1);
        }
        assert(!count); report[size]=0; close(output[0]);
        int status; assert(waitpid(reporter,&status,0)==reporter);
        assert(WIFEXITED(status) && WEXITSTATUS(status)==125);
        assert(strstr(report,"notification conflict reason=") && strstr(report,"completion id=")
            && strstr(report," origin=") && strstr(report," args=[") && strstr(report,"supervisor failure"));
        assert(strstr(report,mismatch==10 ? "duplicate-completion" : "completed-notification-reentry"));
        assert(!ioctl(listener,SECCOMP_IOCTL_NOTIF_ID_VALID,&q->id));
    }
}
static void completed_notification_orders(unsigned order,int descriptor) {
    adoption_calls=0; adoption_error=0;
    int channel[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,channel));
    pid_t parent=getpid(),pid=fork(); assert(pid>=0);
    if (!pid) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL) && getppid()==parent);
        close(channel[0]);
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_openat,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_USER_NOTIF),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
        };
        struct sock_fprog program={sizeof(code)/sizeof(*code),code};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        int fd=syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_NEW_LISTENER,&program);
        assert(fd>=0 && write(channel[1],&fd,sizeof(fd))==sizeof(fd));
        char go; assert(read(channel[1],&go,1)==1);
        /* One call only. The supervisor chooses the order of its kernel
         * restarts and worker callback; no scheduling sleep or stress luck. */
        errno=0;
        assert(syscall(SYS_openat,AT_FDCWD,"/dev/zero",O_RDONLY|O_CLOEXEC,0,0,0)==-1);
        _exit(errno!=(descriptor ? EINTR : EOPNOTSUPP));
    }
    close(channel[1]); int remote;
    assert(read(channel[0],&remote,sizeof(remote))==sizeof(remote));
    int pidfd=syscall(SYS_pidfd_open,pid,0); assert(pidfd>=0);
    listener=syscall(SYS_pidfd_getfd,pidfd,remote,0); assert(listener>=0); close(pidfd);
    assert(!ptrace(PTRACE_SEIZE,pid,NULL,(void *)(uintptr_t)PTRACE_O_EXITKILL));
    assert(write(channel[0],"x",1)==1);
    struct seccomp_notif first=admission_request(),q=first;
    struct thread t={.pid=pid,.tgid=pid,.born=1,.phase=IDLE,.memory=md_memory_new()};
    thread_index[pid%THREAD_BUCKETS]=&t;
    assert(t.memory && !md_memory_buffers_prepare(t.memory,1,4096,0,0));
    if (!order && descriptor) notification_rejections(&t,&q);
    struct md_broker_completion result={.call=first.data,.notification_id=first.id,
        .notification_live=!order,.result={.error=descriptor ? 0 : -EOPNOTSUPP},
        .opened={.fd=descriptor ? open("/dev/zero",O_RDONLY|O_CLOEXEC) : -1}};
    assert(!descriptor || result.opened.fd>=0);
    int status=0;
    if (order) {
        if (order==5) delegate_begin(&t,&q);
        else assert(!ptrace(PTRACE_INTERRUPT,pid,NULL,NULL));
        status=notification_stop(pid);
        assert(ioctl(listener,SECCOMP_IOCTL_NOTIF_ID_VALID,&first.id)<0 && errno==ENOENT);
        assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
        q=admission_request();
        assert(q.id!=first.id && md_notification_same_call(&q.data,&first.data));
        if (order==5) {
            /* Rebind the exact cancelled admission before its pending stop
             * is consumed; the completed result still belongs to first. */
            handle_notification(&q);
            assert(t.phase==DELEGATE_CANCEL && t.metadata.request.id==q.id);
            assert(!ptrace(PTRACE_INTERRUPT,pid,NULL,NULL));
        } else if (order>=2) {
            delegate_begin(&t,&q);
            if (order==3) { t.phase=EXPORT_CANCEL; t.metadata.previous=IDLE; }
            if (order==4) {
                status=notification_stop(pid);
                notification_interrupted(&t);
                assert(t.phase==IDLE);
            }
        }
    }
    completed_notification(pid,&result);
    assert(result.opened.fd==-1 && t.broker_completion);
    if (order==1) handle_notification(&q);
    if (order!=4) status=notification_stop(pid);
    struct rusage usage={0};
    assert(completed_notification_event(&t,status,&usage)==descriptor);
    assert(!t.broker_completion && adoption_calls==descriptor);
    assert(!completed_notification_event(&t,status,&usage));
    assert(adoption_calls==descriptor);
    struct user_pt_regs regs; assert(registers(pid,&regs));
    if (descriptor) {
        assert(t.phase==DISPATCHING && t.broker_event.held && t.fs.call && adopted.fd>=0);
        char byte=1; assert(read(adopted.fd,&byte,1)==1 && !byte);
        md_fs_buffer_open_release(&adopted); t.fs.call=NULL;
        /* FD installation has its own result-custody suite. End this fixture
         * at the proven single adoption without executing its fake adapter. */
        regs=t.execution.registers; regs.regs[0]=-EINTR; assert(set_registers(pid,&regs));
    } else assert(t.phase==IDLE && (long)regs.regs[0]==-EOPNOTSUPP);
    assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
    assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!md_buffer_release(&t.fs.buffer)); md_memory_release(t.memory);
    thread_index[pid%THREAD_BUCKETS]=NULL;
    close(channel[0]); close(listener); listener=-1;
}
static int notification_bind_dispatch(uint64_t *saved,void *unused) {
    (void)unused;
    assert(saved[8]==SYS_openat);
    assert(md_guest_buffer_admit(MD_ENTRY_BIND)==-EAGAIN);
    return 0;
}
static void completed_notification_bind(unsigned order,int descriptor) {
    /* 0/1: retained result, without/with private interruption. 2/3: pending
     * delegation/export. 4/5 and 6/7: interrupt those admissions inside the
     * entry before/after completion; both use the production resume owner. */
    extern char md_guest_buffer_admitted[];
    tasks=md_task_registry_create(); assert(tasks);
    debugger=md_debugger_create(&(struct md_debugger_host){0}); assert(debugger);
    uintptr_t previous_admit=abi.buffer_admit;
    abi.buffer_admit=(uintptr_t)md_guest_buffer_admitted;
    size_t frame_bytes=MD_SVC_POOL_SIZE+MD_SVC_PAYLOAD_SIZE;
    void *frames=mmap((void *)MD_SVC_POOL_BASE,frame_bytes,PROT_READ|PROT_WRITE,
        MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED_NOREPLACE,-1,0);
    assert(frames==(void *)MD_SVC_POOL_BASE);
    uint32_t *code=mmap((void *)0x10000000,4096,PROT_READ|PROT_WRITE,
        MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED_NOREPLACE,-1,0);
    assert(code==(void *)0x10000000);
    code[0]=0xd2800008|(SYS_openat<<5); code[1]=0xd4000001; code[2]=0xd65f03c0;
    __builtin___clear_cache((char *)code,(char *)(code+3));
    assert(!mprotect(code,4096,PROT_READ|PROT_EXEC));
    void *pool=mmap((void *)0x11000000,MD_SVC_CODE_BYTES,PROT_READ|PROT_EXEC,
        MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED_NOREPLACE,-1,0);
    assert(pool==(void *)0x11000000);
    int channel[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,channel));
    pid_t parent=getpid(),pid=fork(); assert(pid>=0);
    if (!pid) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL) && getppid()==parent);
        close(channel[0]);
        struct sock_filter filter[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_openat,1,0),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_gettid,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_USER_NOTIF),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
        };
        struct sock_fprog program={sizeof(filter)/sizeof(*filter),filter};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        int fd=syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_NEW_LISTENER,&program);
        assert(fd>=0 && write(channel[1],&fd,sizeof(fd))==sizeof(fd));
        char go; assert(read(channel[1],&go,1)==1);
        long (*call)(long,const char *,long,long,long,long)=(void *)code;
        assert(call(AT_FDCWD,"/dev/zero",O_RDONLY|O_CLOEXEC,0,0,0)
            ==(descriptor ? -EINTR : -EOPNOTSUPP));
        for (unsigned i=0;i<MD_SVC_SLOT_COUNT;++i)
            assert(!*(uint64_t *)((char *)frames+i*MD_SVC_SLOT_SIZE));
        _exit(0);
    }
    close(channel[1]); int remote;
    assert(read(channel[0],&remote,sizeof(remote))==sizeof(remote));
    int pidfd=syscall(SYS_pidfd_open,pid,0); assert(pidfd>=0);
    listener=syscall(SYS_pidfd_getfd,pidfd,remote,0); assert(listener>=0); close(pidfd);
    assert(!ptrace(PTRACE_SEIZE,pid,NULL,(void *)(uintptr_t)PTRACE_O_EXITKILL));
    assert(write(channel[0],"x",1)==1);
    struct seccomp_notif first=admission_request();
    assert(first.data.instruction_pointer==(uintptr_t)(code+2));
    struct thread t={.pid=pid,.tgid=pid,.born=1,.ready=1,.entered=1,.phase=IDLE,.memory=md_memory_new()};
    thread_index[pid%THREAD_BUCKETS]=&t;
    if (order>=2) {
        delegate_begin(&t,&first);
        if (order&1) { t.phase=EXPORT_CANCEL; t.metadata.previous=IDLE; }
    } else assert(!ptrace(PTRACE_INTERRUPT,pid,NULL,NULL));
    notification_stop(pid);
    struct user_pt_regs original;
    assert(registers(pid,&original));
    assert(t.memory && !md_memory_buffers_prepare(t.memory,1,4096,0,0));
    struct md_svc_registry *r=md_svc_registry_new((uintptr_t)frames,(uintptr_t)notification_bind_dispatch,
        (uintptr_t)md_svc_signal_checkpoint);
    assert(r && !md_memory_svc_set(t.memory,r) && !md_svc_registry_pool(r,(uintptr_t)pool));
    assert(md_svc_registry_direct(r,pid,-1,(uintptr_t)(code+1),SYS_openat)==1);
    struct md_broker_completion result={.call=first.data,.notification_id=first.id,
        .result={.error=descriptor ? 0 : -EOPNOTSUPP},
        .opened={.fd=descriptor ? open("/dev/zero",O_RDONLY|O_CLOEXEC) : -1}};
    assert(!descriptor || result.opened.fd>=0);
    adoption_calls=0; adoption_error=0;
    if (order<2 || order>=6) completed_notification(pid,&result);
    assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
    struct seccomp_notif bind=admission_request();
    assert(bind.data.nr==SYS_gettid && bind.data.args[0]==MD_ENTRY_BIND);
    if (order==1 || order>=4) {
        assert(!ptrace(PTRACE_INTERRUPT,pid,NULL,NULL));
        int status=notification_stop(pid);
        struct user_pt_regs before,after; assert(registers(pid,&before));
        assert(md_svc_registry_context(r,before.pc,before.sp));
        assert(!completed_notification_event(&t,status,NULL));
        assert(!!t.broker_completion==(order==1 || order>=6) && !adoption_calls && registers(pid,&after));
        assert(!memcmp(&before,&after,sizeof(before)));
        if (order>=4) {
            assert(notification_unexecuted(&t));
            assert(interrupted_operation(&t) && t.phase==IDLE && !t.signals.shielded);
        } else assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
        bind=admission_request();
        if (order==4 || order==5) completed_notification(pid,&result);
    }
    handle_notification(&bind);
    assert(!!t.broker_completion==(order<2 || order>=4) && !t.entry.buffer.pool && !adoption_calls);
    struct seccomp_notif retry=admission_request();
    assert(notification_same_call(&t,&first.data,&retry.data));
    handle_notification(&retry);
    if (order==2 || order==3) {
        completed_notification(pid,&result);
        assert(!ptrace(PTRACE_INTERRUPT,pid,NULL,NULL));
    }
    int status=notification_stop(pid);
    assert(completed_notification_event(&t,status,NULL)==descriptor);
    assert(!t.broker_completion && adoption_calls==descriptor);
    struct user_pt_regs returned; assert(registers(pid,&returned));
    if (descriptor) {
        assert(t.phase==DISPATCHING && t.fs.call && adopted.fd>=0);
        char byte=1; assert(read(adopted.fd,&byte,1)==1 && !byte);
        md_fs_buffer_open_release(&adopted); t.fs.call=NULL;
        returned=t.execution.registers; returned.regs[0]=-EINTR; assert(set_registers(pid,&returned));
    }
    assert(returned.sp==original.sp && returned.pc==first.data.instruction_pointer);
    assert(!memcmp(returned.regs+1,original.regs+1,30*sizeof(original.regs[0])));
    assert(returned.pstate==original.pstate);
    assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
    assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!md_buffer_release(&t.fs.buffer)); md_memory_release(t.memory);
    md_task_registry_destroy(tasks); tasks=NULL;
    md_debugger_destroy(debugger); debugger=NULL;
    thread_index[pid%THREAD_BUCKETS]=NULL;
    close(channel[0]); close(listener); listener=-1; abi.buffer_admit=previous_admit;
    assert(!munmap(pool,MD_SVC_CODE_BYTES) && !munmap(code,4096) && !munmap(frames,frame_bytes));
}
static void admission_quiescence(void) {
    extern char md_guest_buffer_admitted[];
    uintptr_t saved=abi.buffer_admit;
    abi.buffer_admit=(uintptr_t)md_guest_buffer_admitted;
    for (unsigned early=0;early<2;++early) {
        int channel[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,channel));
        pid_t parent=getpid(),pid=fork(); assert(pid>=0);
        if (!pid) {
            assert(!prctl(PR_SET_PDEATHSIG,SIGKILL) && getppid()==parent);
            close(channel[0]);
            struct sock_filter code[]={
                BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
                BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_gettid,0,1),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_USER_NOTIF),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
            };
            struct sock_fprog program={sizeof(code)/sizeof(*code),code};
            assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
            int fd=syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_NEW_LISTENER,&program);
            assert(fd>=0);
            uint64_t mask=UINT64_MAX;
            assert(!syscall(SYS_rt_sigprocmask,SIG_SETMASK,&mask,NULL,sizeof(mask)));
            assert(write(channel[1],&fd,sizeof(fd))==sizeof(fd));
            char go; assert(read(channel[1],&go,1)==1);
            assert(md_guest_buffer_admit(42)==-EINTR);
            fd=(int)md_guest_buffer_admit(43);
            char byte=1; assert(fd>=0 && read(fd,&byte,1)==1 && !byte);
            assert(!close(fd)); _exit(0);
        }
        close(channel[1]); int remote;
        assert(read(channel[0],&remote,sizeof(remote))==sizeof(remote));
        int pidfd=syscall(SYS_pidfd_open,pid,0); assert(pidfd>=0);
        listener=syscall(SYS_pidfd_getfd,pidfd,remote,0); assert(listener>=0); close(pidfd);
        assert(!ptrace(PTRACE_SEIZE,pid,NULL,(void *)(uintptr_t)PTRACE_O_EXITKILL));
        assert(write(channel[0],"x",1)==1);
        struct seccomp_notif q=admission_request();
        assert(q.pid==(unsigned)pid && q.data.instruction_pointer==abi.buffer_admit && q.data.args[0]==42);
        struct thread t={.pid=pid,.born=1,.ready=1,.phase=DISPATCHING};
        thread_index[pid%THREAD_BUCKETS]=&t;
        struct mapping_owner execution={0}; mapping_execution=&execution;
        mapping_barrier=md_task_quiesce_new(quiesce_trace); assert(mapping_barrier);
        assert(!md_task_quiesce_add(mapping_barrier,pid,0,PTRACE_CONT));
        if (early) { handle_notification(&q); assert(t.memory_op.notification); }
        assert(!md_task_quiesce_stop(mapping_barrier));
        if (!early) handle_notification(&q);
        assert(!t.memory_op.notification && !t.fs.admission_pending);
        int status;
        /* EVENT_WAIT: the real kernel interrupt; the suite alarm bounds failure. */
        assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status));
        assert((unsigned)status>>16==PTRACE_EVENT_STOP && WSTOPSIG(status)==SIGTRAP);
        assert(!md_task_quiesce_event(mapping_barrier,pid,status,NULL));
        assert(md_task_quiesce_ready(mapping_barrier));
        assert(!md_task_quiesce_release(mapping_barrier));
        q=admission_request();
        assert(q.data.args[0]==42 && q.data.instruction_pointer==abi.buffer_admit);
        struct seccomp_notif_resp reply={.id=q.id,.error=-EINTR};
        assert(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply));
        q=admission_request(); assert(q.data.args[0]==43);
        int source=open("/dev/zero",O_RDONLY|O_CLOEXEC); assert(source>=0);
        struct seccomp_notif_addfd add={.id=q.id,.srcfd=source,
            .flags=SECCOMP_ADDFD_FLAG_SEND,.newfd_flags=O_CLOEXEC};
        assert(ioctl(listener,SECCOMP_IOCTL_NOTIF_ADDFD,&add)>=0); close(source);
        t.fs.admission_id=q.id; t.fs.admission_pending=1;
        buffer_admission_yield(&t); /* Expired publication cannot be replayed. */
        assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
        close(channel[0]); close(listener); listener=-1;
        md_task_quiesce_destroy(mapping_barrier); mapping_barrier=NULL; mapping_execution=NULL;
        thread_index[pid%THREAD_BUCKETS]=NULL;
    }
    abi.buffer_admit=saved;
    puts("PASS internal admission: shielded quiescence before/after notification, exact retry, EINTR and one FD publication");
}
static void descriptor_command_selection(void) {
    const unsigned commands[]={FIOCLEX,FIONCLEX};
    struct md_interception_abi abi={.raw_gate=0x2000000040,.task_return=0x2000000080};
    struct md_interception_program program;
    for (unsigned host=0;host<2;++host) {
        abi.host_policy=host;
        md_interception_program_build(&abi,&program);
        struct sock_fprog filter={program.count,program.code};
        for (unsigned command=0;command<2;++command) for (unsigned high=0;high<2;++high) {
            struct seccomp_data call={.nr=SYS_ioctl,.arch=AUDIT_ARCH_AARCH64,
                .instruction_pointer=0x1000,.args={17,commands[command]|((uint64_t)high<<32),~0UL}};
            uint32_t result;
            assert(!md_seccomp_program_eval(&filter,&call,&result));
            assert(result==(SECCOMP_RET_TRACE|MD_INTERCEPT_FD));
            unsigned long args[6]; memcpy(args,call.args,sizeof(args));
            assert(md_domain_native_call(call.nr,args));
            struct thread t={0};
            struct user_pt_regs regs={.regs={[8]=SYS_ioctl,[0]=17,[1]=args[1],[2]=args[2]}};
            struct seccomp_data request=syscall_description(&regs);
            assert(!domain_call(&t,&request,&regs,MD_INTERCEPT_FD));
        }
    }
    puts("PASS descriptor command selection: shared app/shell filter, unsigned ioctl command and FD owner");
}
static void retained_child_selection(void) {
    struct thread parent={.pid=123,.tgid=100},caller={.pid=101,.tgid=100};
    struct retained_memory retained={.parent=&parent,.ready=1,.retained.actor=456};
    unsigned bucket=456%RETAINED_ACTOR_BUCKETS;
    assert(!retained_actors[bucket] && !retained_memories);
    retained_actors[bucket]=retained_memories=&retained;
    struct private_child_selection selection={.caller=&caller};
    assert(!private_child_matches(&selection,456));
    selection.options=__WALL;
    assert(private_child_matches(&selection,456)==1);
    selection.options=__WCLONE;
    assert(private_child_matches(&selection,456)==1);
    selection.group=456;
    assert(private_child_matches(&selection,456)==1);
    selection.group=100;
    assert(!private_child_matches(&selection,456));
    selection.group=0; caller.tgid=200;
    assert(!private_child_matches(&selection,456));
    pid_t p,g; int signal;
    parent.terminal_stop=1;
    assert(!retained_child_identity(&retained,&p,&g,&signal));
    parent.terminal_stop=0; parent.resume_lost=1;
    assert(!retained_child_identity(&retained,&p,&g,&signal));
    parent.resume_lost=0; retained.ready=0;
    assert(!retained_child_identity(&retained,&p,&g,&signal));
    retained.ready=1;
    retained_detach(&parent);
    assert(!retained.parent);
    parent.pid=789; parent.tgid=789;
    assert(!retained_child_identity(&retained,&p,&g,&signal));
    retained_actors[bucket]=retained_memories=NULL;
    puts("PASS retained child identity: native clone class, process group, parent lifetime and slot reuse");
}
static void descriptor_service_queries(void) {
#define QUERY(name,value) {SYS_##name,value},
    const struct { int number; unsigned command; } queries[]={MD_DESCRIPTOR_SERVICE_QUERIES(QUERY)};
#undef QUERY
    struct md_interception_abi abi={.raw_gate=0x2000000040,.task_return=0x2000000080};
    for (unsigned host=0;host<2;++host) {
        abi.host_policy=host;
        struct md_interception_program program;
        md_interception_program_build(&abi,&program);
        struct sock_fprog filter={program.count,program.code};
        for (unsigned i=0;i<sizeof(queries)/sizeof(*queries);++i)
            for (unsigned high=0;high<2;++high) for (unsigned site=0;site<4;++site) {
                struct seccomp_data call={.nr=queries[i].number,.arch=AUDIT_ARCH_AARCH64,
                    .instruction_pointer=site==0 ? abi.raw_gate : site==1 ? abi.raw_gate+4
                        : site==2 ? abi.raw_gate+(UINT64_C(1)<<32) : 0x1000,
                    .args={17,queries[i].command|((uint64_t)high<<32),~0UL}};
                uint32_t result;
                assert(!md_seccomp_program_eval(&filter,&call,&result));
                int native=!site || (!high && queries[i].number==SYS_fcntl
                    && (queries[i].command==F_GETFD || queries[i].command==F_GETFL));
                assert((result==SECCOMP_RET_ALLOW)==native);
            }
        const struct { int nr; unsigned command; } mutations[]={
            {SYS_fcntl,F_SETFD},{SYS_fcntl,F_DUPFD},{SYS_fcntl,F_DUPFD_CLOEXEC},
            {SYS_ioctl,FIOCLEX},{SYS_ioctl,FIONCLEX},
            {SYS_ioctl,TCSETS},{SYS_ioctl,TCSETS2},{SYS_ioctl,FIONBIO}};
        for (unsigned i=0;i<sizeof(mutations)/sizeof(*mutations);++i) {
            struct seccomp_data call={.nr=mutations[i].nr,.arch=AUDIT_ARCH_AARCH64,
                .instruction_pointer=abi.raw_gate,.args={17,mutations[i].command,~0UL}};
            uint32_t result;
            assert(!md_seccomp_program_eval(&filter,&call,&result));
            assert((result&SECCOMP_RET_ACTION_FULL)==SECCOMP_RET_TRACE);
        }
    }
    puts("PASS descriptor service queries: exact native gate, command width, guest interception and mutation ownership");
}
static void signal_wait_selection(void) {
    struct md_interception_abi abi={.raw_gate=0x2000000040,.task_return=0x2000000080};
    struct md_interception_program program;
    for (unsigned host=0;host<2;++host) {
        abi.host_policy=host;
        for (unsigned input=0;input<3;++input) for (unsigned site=0;site<3;++site) {
            struct seccomp_data query={.nr=SYS_rt_sigprocmask,.arch=AUDIT_ARCH_AARCH64,
                .instruction_pointer=site==0 ? 0x1000 : site==1 ? abi.raw_gate : abi.task_return};
            query.args[1]=input==2 ? UINT64_C(1)<<32 : input;
            uint32_t result;
            md_interception_program_build(&abi,&program);
            struct sock_fprog base={program.count,program.code};
            assert(!md_seccomp_program_eval(&base,&query,&result));
            assert((result==SECCOMP_RET_USER_NOTIF)==(host && !input && !site));
            query.arch=0;
            assert(!md_seccomp_program_eval(&base,&query,&result) && result==SECCOMP_RET_KILL_PROCESS);
            query.arch=AUDIT_ARCH_AARCH64;
            md_signal_program_build(&abi,&program);
            struct sock_fprog signals={program.count,program.code};
            assert(!md_seccomp_program_eval(&signals,&query,&result));
            assert(result==((site || (host && !input)) ? SECCOMP_RET_ALLOW
                : SECCOMP_RET_TRACE|MD_INTERCEPT_SIGNAL));
        }
    }
    abi.host_policy=0;
    for (unsigned resident=0;resident<2;++resident) for (unsigned blocked=0;blocked<2;++blocked) {
        struct md_entry_channel channel={0};
        uint64_t native=UINT64_C(1)<<(SIGUSR1-1),saved=UINT64_C(1)<<(SIGUSR2-1);
        struct thread t={.signals.host_blocked=blocked,.execution.mask=saved,.signals.host=1,
            .entry.execution=resident ? &channel : NULL,.signals.pending=md_signal_pending_new()};
        assert(t.signals.pending);
        assert(!signal_return_pending(&t));
        uint64_t logical=blocked ? MD_SIGNAL_SYS_BIT : 0;
        assert(signal_query_value(&t,SYS_rt_sigprocmask,native)==((resident ? native : saved)|logical));
        assert(signal_query_value(&t,SYS_rt_sigpending,native|saved|MD_SIGNAL_SYS_BIT)
            ==((resident ? native : 0)|saved));
        siginfo_t info={.si_signo=SIGSYS};
        assert(md_signal_pending_put(t.signals.pending,MD_SIGNAL_PRIVATE,&info)==1);
        assert(signal_return_pending(&t)==!blocked);
        assert(signal_query_value(&t,SYS_rt_sigpending,native|saved)==((resident ? native : 0)|saved|logical));
        md_signal_pending_clear(t.signals.pending,1);
        assert(md_signal_pending_put(t.signals.pending,MD_SIGNAL_SHARED,&info)==1);
        assert(signal_return_pending(&t)==!blocked);
        assert(signal_query_value(&t,SYS_rt_sigpending,0)==logical);
        t.signals.delivery.active=1; t.signals.delivery.mask=native|saved;
        assert(signal_query_value(&t,SYS_rt_sigprocmask,0)==(native|saved|logical));
        t.signals.host=0;
        assert(signal_query_value(&t,SYS_rt_sigpending,native|MD_SIGNAL_SYS_BIT)==(native|MD_SIGNAL_SYS_BIT));
        assert(signal_query_value(&t,SYS_rt_sigprocmask,saved|MD_SIGNAL_SYS_BIT)==(saved|MD_SIGNAL_SYS_BIT));
        md_signal_pending_release(t.signals.pending);
    }
    md_signal_program_build(&abi,&program);
    struct sock_fprog filter={program.count,program.code};
    struct md_seccomp_policy *policy=NULL;
    assert(!md_seccomp_policy_capture(NULL,&filter,&policy));
    const long calls[]={SYS_ppoll,SYS_pselect6,SYS_epoll_pwait,SYS_epoll_pwait2};
    const unsigned args[]={3,5,4,4};
    for (unsigned i=0;i<4;++i) for (unsigned high=0;high<2;++high) {
        struct seccomp_data call={.nr=calls[i],.instruction_pointer=0x1000};
        uint32_t result;
        assert(!md_seccomp_policy_eval(policy,&call,&result) && result==SECCOMP_RET_ALLOW);
        call.args[args[i]]=UINT64_C(1)<<(high ? 32 : 0);
        assert(!md_seccomp_policy_eval(policy,&call,&result) && result==(SECCOMP_RET_TRACE|MD_INTERCEPT_SIGNAL));
        call.instruction_pointer=abi.task_return;
        assert(!md_seccomp_policy_eval(policy,&call,&result) && result==SECCOMP_RET_ALLOW);
    }
    md_seccomp_policy_release(policy);
    struct md_signal_wait_view view={.saved=MD_SIGNAL_SYS_BIT|(UINT64_C(1)<<(SIGUSR1-1)),
        .temporary=UINT64_C(1)<<(SIGUSR2-1),.active=1};
    uint64_t saved=0;
    struct md_kernel_action action={0};
    uint64_t active=md_signal_view_enter(SIGSYS,&action,0,&view,&saved);
    assert(saved==view.saved && active==(view.temporary|MD_SIGNAL_SYS_BIT));
    struct md_signal_actions *actions=md_signal_actions_new(); assert(actions);
    const int signals[]={SIGALRM,SIGSYS};
    for (unsigned i=0;i<2;++i) for (unsigned defer=0;defer<2;++defer) {
        struct md_task_signal_control control;
        ucontext_t frame={0};
        uint64_t before=view.saved&~MD_SIGNAL_SYS_BIT,saved=0;
        memcpy(&frame.uc_sigmask,&before,sizeof(before));
        struct user_pt_regs regs={.regs={signals[i],0,(uintptr_t)&frame}};
        action=(struct md_kernel_action){.handler=0x1234,.flags=defer ? SA_NODEFER : 0,
            .mask=UINT64_C(1)<<(SIGTRAP-1)};
        int blocked=0;
        uint64_t expected=view.temporary|action.mask;
        if (!defer) expected|=UINT64_C(1)<<(signals[i]-1);
        unsigned char scratch[sizeof(struct md_task_copy_scratch)+sizeof(uint64_t)];
        assert(md_task_signal_wait_entry_begin(&control,no_trace,getpid(),&regs,0x1000,0x2000,
            actions,&blocked,&action,&view,scratch,(uintptr_t)scratch,sizeof(scratch))==1);
        memcpy(&saved,&frame.uc_sigmask,sizeof(saved));
        assert(!control.result && !control.copying && !control.invoking
            && saved==view.saved && control.previous_mask==view.saved
            && control.next_mask==expected && blocked==!!(expected&MD_SIGNAL_SYS_BIT));
    }
    md_signal_actions_release(actions);
    puts("PASS signal wait selection: null/high pointers, service gates and distinct saved/active masks");
}
static void signal_descriptor_rollback(void) {
    assert(!loan_retirements && !task_action_calls);
    int previous=fd_ready; fd_ready=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK); assert(fd_ready>=0);
    struct thread t={.pid=123};
    t.resources.files=md_fd_origins_new(); assert(t.resources.files);
    struct signal_control *owner=calloc(1,sizeof(*owner)); assert(owner); t.signals.control=owner;
    assert(md_fd_order_begin(t.resources.files,&owner->files,NULL)==1);
    assert(!md_fd_order_start(&owner->files));
    owner->descriptor=md_signal_fd_object(md_signal_fd_new()); assert(owner->descriptor);
    assert(!md_fd_origins_prepare_object(t.resources.files,owner->descriptor,0,&owner->publication));
    owner->loan=calloc(1,sizeof(*owner->loan)); assert(owner->loan);
    owner->loan->loan_table=md_fd_origins_pin(t.resources.files);
    owner->loan->loan=md_fd_loan_new(t.resources.files); assert(owner->loan->loan);
    assert(!md_fd_origins_prepare_custody(&owner->publication,owner->loan->loan,&owner->files));
    owner->loan->number=17;
    signal_descriptor_selected(&t,-ENOMEM);
    assert(owner->retiring && owner->retire_error==-ENOMEM && !owner->loan && !owner->publication.table);
    struct task_action *action=t.action;
    assert(action && action==loan_retirements && action->number==17 && action->loan_turn==&owner->files);
    assert(md_fd_order_state(&owner->files)==MD_RESOURCE_ORDER_RUNNING);
    assert(md_fd_origins_loan_matches(&owner->files,17,action->loan)==1);
    unsigned flags=FD_CLOEXEC;
    struct md_fd_object *object=md_fd_origins_object(t.resources.files,17,&flags);
    assert(object==owner->descriptor && !flags); md_fd_object_release(object);
    assert(!md_fd_origins_close_range(t.resources.files,17,17,0));
    t.action=NULL; task_action_release(action);
    signal_control_discard(&t); assert(!t.signals.control && !loan_retirements && !task_action_calls);
    md_fd_origins_release(t.resources.files); close(fd_ready); fd_ready=previous;
    puts("PASS signalfd rollback: exact provisional slot, retained table turn, real flags and shared retirement owner");
}
static void signal_frame_records(void) {
    struct md_signal_frame frame={0};
    unsigned char *data=frame.context.uc_mcontext.__reserved;
    assert(md_signal_frame_relocatable(&frame));
    uint32_t header[]={0x46508001,528};
    memcpy(data,header,sizeof(header));
    assert(md_signal_frame_relocatable(&frame));
    const uint32_t rejected[][2]={{0x45585401,32},{0xdeadbeef,16},
        {0x46508001,0},{0x46508001,8},{0x46508001,17},
        {0x46508001,UINT32_MAX},{0,16},{0x46508001,sizeof(frame.context.uc_mcontext.__reserved)}};
    for (unsigned i=0;i<sizeof(rejected)/sizeof(*rejected);++i) {
        memcpy(data,rejected[i],sizeof(*rejected));
        assert(!md_signal_frame_relocatable(&frame));
    }
    puts("PASS signal frame records: bounded relocation, terminator, EXTRA/unknown fallback and malformed lengths");
}
static void socket_wait_selection(void) {
    const long calls[]={SYS_recvfrom,SYS_sendto,SYS_sendmmsg,SYS_recvmsg,SYS_sendmsg};
    for (unsigned i=0;i<sizeof(calls)/sizeof(*calls);++i) {
        unsigned flags=calls[i]==SYS_recvmsg || calls[i]==SYS_sendmsg ? 2 : 3;
        unsigned long args[6]={0};
        assert(md_socket_wait_call(calls[i],args[2],args[3]));
        args[flags]=MSG_PEEK|MSG_WAITALL;
        assert(md_socket_wait_call(calls[i],args[2],args[3]));
        args[flags]|=MSG_DONTWAIT;
        assert(!md_socket_wait_call(calls[i],args[2],args[3]));
    }
    unsigned long args[6]={0,0,0,SOCK_NONBLOCK|SOCK_CLOEXEC};
    /* accept4 flags affect the returned descriptor, not the listener's wait. */
    assert(md_socket_wait_call(SYS_accept4,args[2],args[3]));
    assert(md_socket_wait_call(SYS_connect,args[2],args[3]));
    assert(!md_socket_wait_call(SYS_getsockname,args[2],args[3]));
    assert(!md_socket_wait_call(SYS_bind,args[2],args[3]));
    puts("PASS native socket waits: per-call nonblocking flags and independent accept4 flags");
}
static unsigned control_continuations;
static void svc_debugger_ownership(void) {
    assert(!threads && !debugger);
    size_t size=2*MD_SVC_POOL_SIZE+MD_SVC_PAYLOAD_SIZE;
    void *allocation=mmap(NULL,size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(allocation!=MAP_FAILED);
    uintptr_t frames=((uintptr_t)allocation+MD_SVC_POOL_SIZE-1)&~(uintptr_t)(MD_SVC_POOL_SIZE-1);
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!ptrace(PTRACE_TRACEME,0,NULL,NULL));
        raise(SIGSTOP);
        _exit(0);
    }
    int status; assert(waitpid(child,&status,0)==child && WIFSTOPPED(status));
    struct thread owner={.pid=child,.memory=md_memory_new(),.debug.options=PTRACE_O_TRACEEXIT};
    struct thread peer={.pid=123,.memory=owner.memory,.debug.options=PTRACE_O_TRACEEXIT};
    struct thread other={.pid=124,.memory=md_memory_new(),.debug.options=PTRACE_O_TRACEEXIT};
    assert(owner.memory && other.memory);
    struct md_svc_registry *r=md_svc_registry_new(frames,0x4000,0x5000); assert(r);
    assert(!md_memory_svc_set(owner.memory,r));
    threads=&owner; owner.next=&peer; peer.next=&other;
    assert(svc_entry_observe(&owner) && r->disabled && r->pause_reasons==MD_SVC_PAUSE_DEBUGGER);
    owner.debug.options=0;
    assert(svc_entry_observe(&owner) && r->disabled);
    peer.pid=0;
    assert(svc_entry_observe(&owner) && !r->disabled && !r->pause_reasons);
    peer.pid=123; owner.debug.options=PTRACE_O_TRACEEXIT;
    assert(svc_entry_observe(&owner) && r->disabled);
    owner.debug.options=peer.debug.options=0;
    assert(svc_entry_observe(&owner) && !r->disabled);
    owner.debug.options=PTRACE_O_TRACEEXIT;
    assert(svc_entry_observe(&owner) && svc_revoke_entry(&owner));
    owner.debug.options=0;
    assert(svc_entry_observe(&owner) && r->disabled && !r->pause_reasons);
    threads=NULL;
    md_memory_release(other.memory); md_memory_release(owner.memory);
    assert(!ptrace(PTRACE_CONT,child,NULL,NULL));
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!munmap(allocation,size));
    puts("PASS SVC debugger ownership: last shared-mm owner, retired peer, unrelated mm and permanent revocation");
}
static void filter_registration_ownership(void) {
    assert(!threads);
    struct thread owner={.pid=123,.memory=md_memory_new()},peer={.pid=124},other={.pid=125};
    assert(owner.memory);
    peer.memory=owner.memory; other.memory=md_memory_new(); assert(other.memory);
    struct filter_install first={0},second={0};
    owner.seccomp.install=&first; other.seccomp.install=&second;
    threads=&owner; owner.next=&peer; peer.next=&other;
    int filtered=-1;
    assert(filter_entry_shared_policy(&owner,&filtered) && !filtered);
    peer.entry.fence_waiting=1;
    assert(!filter_entry_shared_policy(&owner,&filtered));
    peer.entry.fence_waiting=0; peer.seccomp.install=&second;
    assert(!filter_entry_shared_policy(&owner,&filtered));
    peer.seccomp.install=NULL;
    assert(filter_entry_shared_policy(&owner,&filtered));
    peer.pid=0; peer.seccomp.install=&second;
    assert(filter_entry_shared_policy(&owner,&filtered));
    threads=NULL;
    md_memory_release(other.memory); md_memory_release(owner.memory);
    puts("PASS filter registration: shared policy waits for queued/active installers, not unrelated or retired tasks");
}
static void control_checkpoint_resume(struct thread *t) {
    assert(!t->entry.stop.resume && t->entry.stop.reason==MD_CONTEXT_COUNT);
    assert(t->phase==DISPATCHING && t->control.result==123);
    assert(t->fs.request.command==MD_BUFFER_EPOLL_BEGIN && t->fs.registers.pc==0x4004);
    ++control_continuations;
}
static long control_completed_checkpoint(int request,pid_t pid,void *address,void *data) {
    assert(request==PTRACE_SETREGSET && pid==123 && (uintptr_t)address==NT_PRSTATUS);
    const struct iovec *io=data;
    const struct user_pt_regs *regs=io->iov_base;
    assert(io->iov_len==sizeof(*regs) && regs->pc==0x4004 && regs->regs[0]==37);
    ++control_continuations;
    errno=ESRCH; return -1;
}
static void execution_request_boundary(void) {
    struct user_pt_regs registers={.pc=0x1004,.sp=0x2000,.pstate=0xa0000000};
    for (unsigned i=0;i<31;++i) registers.regs[i]=UINT64_C(0xf000000000000000)+i;
    registers.regs[8]=SYS_umask;
    struct thread t={0};
    execution_capture(&t,&registers);
    assert(!memcmp(&t.execution.registers,&registers,sizeof(registers)));
    assert(t.execution.request.nr==SYS_umask && t.execution.request.arch==AUDIT_ARCH_AARCH64);
    assert(t.execution.request.instruction_pointer==registers.pc);
    for (unsigned i=0;i<6;++i) assert(t.execution.request.args[i]==registers.regs[i]);
    struct seccomp_data admitted=t.execution.request;
    struct user_pt_regs continuation=registers;
    continuation.regs[8]=SYS_gettid; continuation.regs[0]=123; continuation.pc+=16;
    execution_retain(&t,&admitted,&continuation);
    assert(!memcmp(&t.execution.request,&admitted,sizeof(admitted)));
    assert(!memcmp(&t.execution.registers,&continuation,sizeof(continuation)));
    execution_retain(&t,&admitted,NULL);
    assert(!memcmp(&t.execution.registers,&continuation,sizeof(continuation)));
    descriptor_retain(&t,&admitted,&continuation);
    descriptor_retain(&t,&admitted,NULL);
    assert(!memcmp(&t.descriptors.call,&admitted,sizeof(admitted)));
    assert(!memcmp(&t.descriptors.registers,&continuation,sizeof(continuation)));
    /* A resident request has no machine frame. Poison the retained frame to
     * ensure common execution reads only the admitted description. */
    memset(&t.execution.registers,0xa5,sizeof(t.execution.registers));
    struct md_guest_execution call=execution_prepare(&t);
    assert(call.number==SYS_umask && call.control==-1 && !call.prepared);
    for (unsigned i=0;i<6;++i) assert(call.args[i]==registers.regs[i]);
    t.phase=DISPATCHING; t.control.result=123;
    registers.pc=0x4004; registers.regs[0]=MD_BUFFER_EPOLL_BEGIN;
    struct supervisor_control_request control=control_description(&registers);
    t.fs.request=control;
    t.entry.stop.reason=MD_CONTEXT_WAIT; t.entry.stop.resume=control_checkpoint_resume;
    control_continuations=0;
    buffer_control(&t,&control,&registers);
    assert(control_continuations==1 && t.control.result==123);
    t.entry.stop.reason=MD_CONTEXT_DESCRIPTOR; t.entry.stop.resume=control_checkpoint_resume;
    cooperative_buffer_release(&t);
    assert(!t.entry.stop.resume && t.entry.stop.reason==MD_CONTEXT_COUNT && control_continuations==1);
    t.pid=123; t.entry.stop.reason=MD_CONTEXT_WAIT; t.entry.stop.resume=control_park;
    t.fs.request.command=control.command=MD_BUFFER_PROC_IO;
    registers.regs[0]=MD_BUFFER_PROC_IO;
    control_park(&t);
    control_reply(&t,37);
    control_park(&t);
    assert(t.control.done && t.control.result==37 && t.entry.stop.resume==control_park);
    thread_index[t.pid%THREAD_BUCKETS]=&t; checkpoints.trace=control_completed_checkpoint;
    buffer_control(&t,&control,&registers);
    assert(control_continuations==2 && !t.control.done && !t.entry.stop.resume && t.resume_lost);
    checkpoints.trace=checkpoint_native_trace; thread_index[t.pid%THREAD_BUCKETS]=NULL;
    puts("PASS execution request: transport-neutral arguments, exact checkpoint continuation and cancellation");
}
static void execution_completion(void) {
    const struct { long value; enum execution_continuation next; } cases[]={
        {0,EXECUTION_RETURN},{123,EXECUTION_RETURN},{-EFAULT,EXECUTION_RETURN},
        {RESTART_SIGNAL,EXECUTION_RESTART},{RESTART_PREPARATION,EXECUTION_RESTART},
        {MD_IPC_WAIT,EXECUTION_IPC_WAIT},{MD_WATCH_WAIT,EXECUTION_WATCH_WAIT},
        {MD_CALL_NATIVE,EXECUTION_NATIVE}};
    for (unsigned i=0;i<sizeof(cases)/sizeof(*cases);++i) {
        struct thread t={.pid=123,.phase=DISPATCHING,.execution.stack=0x8000,.execution.mask=0x400,
            .execution.registers={.pc=0x1004,.sp=0x2000,.regs={[8]=SYS_uname}},.execution.epoll_started=1};
        execution_capture(&t,&t.execution.registers);
        struct user_pt_regs original=t.execution.registers;
        assert(execution_complete(&t,cases[i].value)==cases[i].next);
        assert(!t.execution.epoll_started && !t.resume_lost && t.phase==DISPATCHING);
        assert(t.execution.stack==0x8000 && t.execution.mask==0x400);
        assert(!memcmp(&original,&t.execution.registers,sizeof(original)));
    }
    for (int filter=0;filter<2;++filter) for (int error=0;error<2;++error) {
        struct thread t={.pid=123,.phase=DISPATCHING,.process_op.preflight=!filter,.seccomp.preflight=filter,
            .debug.call_active=1,.debug.adapter=1,.execution.stack=0x8000};
        assert(execution_complete(&t,error ? -EINVAL : 0)
            ==(error ? EXECUTION_PREFLIGHT_RETURN : EXECUTION_PREFLIGHT_REPLAY));
        assert(!t.process_op.preflight && !t.seccomp.preflight && t.entry.registration_revoked==filter);
        assert(t.debug.replay_entry==!error && t.debug.adapter==error);
        assert(!t.resume_lost && t.phase==DISPATCHING && t.execution.stack==0x8000);
    }
    struct thread clone={.pid=123,.phase=DISPATCHING,.execution.request={.nr=SYS_clone3},
        .memory=md_memory_new(),.execution.stack=0x8000};
    assert(clone.memory && !md_memory_buffers_prepare(clone.memory,1,4096,0,0));
    assert(!md_buffers_take(md_memory_buffers(clone.memory),&clone.fs.buffer));
    assert(execution_complete(&clone,MD_CALL_CLONE)==EXECUTION_CLONE);
    assert(clone.process_op.clone_prepared && clone.fs.buffer.pool && clone.execution.stack==0x8000 && !clone.resume_lost);
    assert(!md_buffer_release(&clone.fs.buffer)); md_memory_release(clone.memory);
    puts("PASS execution completion: resource retirement and continuations without register or resume transport");
}
static void execution_register_boundary(void) {
    struct thread t={.execution.stack=0x8000,.execution.registers={.pc=0x1004,.sp=0x2000,.pstate=0xa0000000}};
    for (unsigned i=0;i<31;++i) t.execution.registers.regs[i]=UINT64_C(0xf000000000000000)+i;
    struct md_interception_abi saved=abi;
    abi.dispatch=0x4000; abi.done_entry=0x5000;
    struct md_guest_execution call={.number=SYS_recvmsg,.policy=MD_DISPATCH_PATHS|MD_DISPATCH_NETWORK_SCOPED,
        .prepared=0x9000};
    for (unsigned i=0;i<6;++i) call.args[i]=UINT64_C(0xe000000000000000)+i;
    for (int kind=-1;kind<8;++kind) {
        call.control=kind;
        struct user_pt_regs r=dispatch_registers(&t,&call);
        assert(r.pc==abi.dispatch && r.sp==t.execution.stack && r.regs[30]==abi.done_entry);
        assert(r.regs[0]==(unsigned long)call.number && r.regs[11]==call.prepared);
        assert((int32_t)r.regs[10]==kind && r.regs[9]==call.policy);
        for (unsigned i=0;i<6;++i) assert(r.regs[i+1]==call.args[i]);
        assert(r.regs[7]==t.execution.registers.regs[7] && r.regs[8]==t.execution.registers.regs[8]);
        for (unsigned i=12;i<30;++i) assert(r.regs[i]==t.execution.registers.regs[i]);
        assert(r.pstate==t.execution.registers.pstate && t.execution.registers.pc==0x1004 && t.execution.registers.sp==0x2000);
    }
    abi=saved;
    puts("PASS execution register boundary: full-width arguments, typed control, prepared input and preserved caller");
}
static void descriptor_capture_admission(void) {
    struct md_interception_abi saved=abi;
    abi.capture_gate=0x1004;
    struct thread t={.memory=md_memory_new()}; assert(t.memory);
    assert(!md_memory_buffers_prepare(t.memory,1,sizeof(struct md_fs_buffer_frame),0,0));
    assert(!md_buffers_take(md_memory_buffers(t.memory),&t.fs.buffer));
    uintptr_t path=md_memory_buffer_address(t.memory,&t.fs.buffer)+offsetof(struct md_fs_buffer_frame,source);
    struct seccomp_data r={.instruction_pointer=abi.capture_gate,.nr=SYS_openat,
        .args={(unsigned long)AT_FDCWD,path,O_PATH|O_CLOEXEC}};
    const int origins[]={0,17,AT_FDCWD};
    for (unsigned i=0;i<sizeof(origins)/sizeof(*origins);++i) for (unsigned directory=0;directory<2;++directory) {
        r.args[4]=(unsigned long)origins[i];
        r.args[2]=O_PATH|O_CLOEXEC|(directory ? O_DIRECTORY : 0);
        assert(fd_capture_valid(&t,&r));
        for (unsigned invalid=0;invalid<7;++invalid) {
            struct seccomp_data bad=r;
            if (invalid==0) bad.instruction_pointer+=4;
            if (invalid==1) bad.nr=SYS_openat2;
            if (invalid==2) bad.args[0]=17;
            if (invalid==3) bad.args[3]=0600;
            if (invalid==4) bad.args[2]|=O_TRUNC;
            if (invalid==5) bad.args[2]&=~O_CLOEXEC;
            if (invalid==6) bad.args[4]=(unsigned long)-1;
            assert(!fd_capture_valid(&t,&bad));
        }
    }
    r.args[4]=(unsigned long)MD_FS_BUFFER_SOURCE;
    for (unsigned caller=0;caller<2;++caller) for (unsigned append=0;append<2;++append)
            for (unsigned directory=0;directory<2;++directory) {
        t.fs.open.fd=17;
        t.fs.open.flags=O_RDWR|O_CLOEXEC|O_NOFOLLOW|(append ? O_APPEND : 0)|(directory ? O_DIRECTORY : 0);
        t.fs.open.completion.kind=caller ? MD_OPEN_CALLER : MD_OPEN_READY;
        r.args[2]=caller ? t.fs.open.flags
            : O_PATH|(t.fs.open.flags&(O_CLOEXEC|O_DIRECTORY|O_NOFOLLOW));
        assert(fd_capture_valid(&t,&r));
        for (unsigned invalid=0;invalid<4;++invalid) {
            struct seccomp_data bad=r;
            if (invalid==0) ++bad.args[1];
            if (invalid==1) bad.args[2]|=O_TRUNC;
            if (invalid==2) bad.args[2]^=O_CLOEXEC;
            if (invalid==3) t.fs.open.fd=-1;
            assert(!fd_capture_valid(&t,&bad));
            t.fs.open.fd=17;
        }
        if (caller && append) { r.args[2]&=~O_APPEND; assert(fd_capture_valid(&t,&r)); }
    }
    assert(!md_buffer_release(&t.fs.buffer)); md_memory_release(t.memory); abi=saved;
    puts("PASS descriptor capture: exact carrier, ordinary and pinned paths, O_PATH flags and caller append retry");
}
static unsigned deferred_resumes;
static long deferred_trace(int request,pid_t pid,void *address,void *data) {
    assert(request==PTRACE_CONT && pid==123 && !address && !data);
    struct thread *t=find_thread(pid); assert(t);
    if (!deferred_resumes++) {
        t->memory_op.resume=1;
        task_continuation_defer(t);
        /* Nested release cannot recurse through or lose this continuation. */
        task_action_unpark();
    }
    return 0;
}
static void deferred_continuations(void) {
    assert(!task_continuations.lists[0].first && !task_continuations.lists[1].first);
    struct thread t={.pid=123,.terminal_stop=1,.kernel_stopped=1};
    struct thread unrelated={.pid=456,.resume_lost=1,.memory_op.resume=1};
    struct thread *saved=threads;
    threads=&unrelated;
    int saved_fd=fd_ready; fd_ready=eventfd(0,EFD_NONBLOCK|EFD_CLOEXEC); assert(fd_ready>=0);
    thread_index[t.pid%THREAD_BUCKETS]=&t; checkpoints.trace=deferred_trace;
    deferred_resumes=0; t.memory_op.resume=1;
    task_continuation_defer(&t); task_continuation_defer(&t);
    task_action_unpark();
    assert(deferred_resumes==1 && t.memory_op.resume && t.memory_op.continuation.list);
    assert(task_continuations_ready && unrelated.memory_op.resume);
    task_continuations_progress();
    assert(deferred_resumes==2 && !t.memory_op.resume && !t.memory_op.continuation.list);
    assert(!task_continuations_ready && unrelated.memory_op.resume);
    task_continuations_progress(); assert(deferred_resumes==2);
    t.memory_op.resume=1; t.memory_op.signal=SIGUSR1;
    t.memory_op.notification=calloc(1,sizeof(*t.memory_op.notification)); assert(t.memory_op.notification);
    task_continuation_defer(&t); task_continuation_discard(&t);
    task_action_unpark();
    assert(deferred_resumes==2 && !t.memory_op.resume && !t.memory_op.signal
        && !t.memory_op.notification && !t.memory_op.continuation.list);
    checkpoints.trace=checkpoint_native_trace; thread_index[t.pid%THREAD_BUCKETS]=NULL;
    threads=saved; close(fd_ready); fd_ready=saved_fd;
    puts("PASS deferred continuations: queued owners only, deduplication, nested release and terminal cancellation");
}
int main(void) {
    md_page_size=(size_t)sysconf(_SC_PAGESIZE);
    deferred_continuations();
    execution_request_boundary();
    filter_registration_ownership();
    svc_debugger_ownership();
    execution_completion();
    execution_register_boundary();
    descriptor_capture_admission();
    retained_continuation_custody();
    resident_file_waiter_custody();
    repair_late_terminal();
    socket_wait_selection();
    signal_frame_records();
    filesystem_control_metadata();
    borrowed_interrupt();
    borrowed_continuations();
    native_child_membership();
    private_wait_selection();
    private_wait_buffer_cancel();
    parked_checkpoint_ownership();
    native_signal_entry();
    notification_signal_entry();
    pending_signal_wakeup();
    exclusive_signal_entry();
    native_signal_query();
    logical_signal_frame();
    direct_signal_query();
    signal_buffer_pressure();
    external_signal_target();
    policy_signal_observation();
    signal_descriptor_rollback();
    descriptor_command_selection();
    descriptor_service_queries();
    retained_child_selection();
    signal_wait_selection();
    pending_queue_scan(); pending_signal_ownership(); signalfd_information();
    debug_host_exit();
    cancel_stopped_child();
    filter_publication();
    filter_listener_ownership();
    trace_policy_admission();
    filter_birth_order();
    filter_replay_ownership();
    receive_error_queue();
    alarm(30);
    event_completion();
    lost_stop_delivery();
    orphan_loan_retirement();
    queued_loan_retirement();
    resident_loan_retirement();
    resident_loan_abandonment();
    readiness_local_duplicate();
    readiness_transport_pressure();
    signal_shield_ownership();
    typed_transfer_ownership();
    metadata_mask_mutation();
    metadata_replay_completion();
    metadata_policy_delivery();
    readiness_publication_eviction();
    readiness_terminal_replacement();
    process_snapshot();
    memory_context_capture();
    memory_shared_mutations();
    memory_observation_routes();
    entry_revocation_lost_stop();
    machine_context_admission();
    native_alias_routing();
    execution_context_contract();
    entry_wait_ownership();
    processor_controls();
    svc_client_lifetime();
    receive_layouts();
    native_capture();
    duplicate_retirement_order();
    fd_native_retirement();
    duplicate_prepared_admission();
    fd_shared_mutations(); pidfd_shared_birth(); process_shared_waits(); close_shared_completion();
    native_ioctl_route();
    readiness_ignored_effect();
    queued_actions();
    barrier_admission(); readiness_admission_order(); readiness_entry_interrupt(); readiness_signal_return(); readiness_parked_signal_return(); readiness_cancel_entry(); readiness_admission_identity(); entry_fence_ownership(); control_deadlines(); pid_collection_waiters(); terminal_buffer_completion(); ioctl_publication_completion(); notification_queue(); virtual_listener_lifetime(); notification_policy_binding(); md_test_seccomp_notify(); buffered_io_pressure(); prepared_input(); captured_writes(); repair_events(); repair_retirement(); quiescent_birth(); event_accounting();
    for (unsigned mode=0;mode<4;++mode) {
        completed_open(mode,SIGUSR1); completed_open(mode,SIGRTMAX);
    }
    provider_wait_restarts(); provider_wait_boundaries(); debugger_wait_cancellation(); debugger_shared_waits();
    process_wait_signal_receipts();
    memory_shared_detach();
    adapter_native_receipts();
    duplicate_entry_receipts();
    mapping_shared_provider();
    mapping_shared_admission();
    scalar_stopped_transport();
    signal_group_resource();
    thread_exit_target();
    image_entry_identity();
    for (unsigned order=0;order<6;++order) for (int descriptor=0;descriptor<2;++descriptor)
        completed_notification_orders(order,descriptor);
    puts("PASS notification completion: six kernel cancellation/reentry orders, FD/error custody and conflict rejection");
    for (unsigned order=0;order<8;++order) for (int descriptor=0;descriptor<2;++descriptor)
        completed_notification_bind(order,descriptor);
    puts("PASS notification bind: live lazy patch, private-stack interruption and single retained FD/error delivery");
    terminal_cleanup_custody();
    retirement_preparation_terminal();
    admission_quiescence();
    checkpoint_admission();
    checkpoint_quiescence();
    quiescent_task_ownership();
    debugger_quiescence();
    debugger_provider_wait();
    md_task_checkpoints_clear(&checkpoints);
    puts("PASS supervisor completed open: exhausted buffer, failed adoption and single standard/realtime delivery");
    return 0;
}
