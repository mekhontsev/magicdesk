/* Compile the actual coordinator; only external admission/accounting boundaries
 * are fault-injected. No duplicate model of its state machine or product hooks. */
#define main supervisor_program_main
#include "../shroot/src/supervisor.c"
#undef main
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
        t->terminal_stop=0; t->robust_terminal=1;
        assert(!retained_retirement_group_ready(parent.pid));
        t->robust_terminal=0;
        assert(retained_retirement_group_ready(parent.pid));
    }
    threads=saved;
    assert(!kill(child,SIGKILL));
    int status; assert(waitpid(child,&status,0)==child && WIFSIGNALED(status));
    assert(md_task_parent(child)==-ENOENT);
    puts("PASS native child classification and retirement: parent/child exit ownership, wait class and parenthesized comm");
}
static int wait_test_hidden,wait_test_done;
static pid_t wait_test_last;
static long wait_test_result;
static enum md_debugger_wait_action wait_test_action;
static struct user_pt_regs wait_test_probe;
static int wait_test_children(pid_t pid,const struct user_pt_regs *regs,pid_t **candidates,size_t *count) {
    (void)pid; (void)regs;
    *candidates=NULL; *count=0;
    if (wait_test_hidden && wait_test_last) {
        *candidates=calloc(wait_test_last,sizeof(**candidates)); assert(*candidates);
        *count=wait_test_last;
        for (pid_t i=0;i<wait_test_last;++i) (*candidates)[i]=i+1;
    }
    return wait_test_hidden;
}
static void wait_test_complete(pid_t pid,struct user_pt_regs *regs,long result) {
    (void)pid; (void)regs;
    ++wait_test_done; wait_test_result=result;
}
static void wait_test_run(pid_t pid,const struct user_pt_regs *regs,enum md_debugger_wait_action action) {
    (void)pid;
    wait_test_probe=*regs; wait_test_action=action;
}
static void private_wait_selection(void) {
    struct md_debugger_host host={.wait_children=wait_test_children,.complete=wait_test_complete,.wait=wait_test_run};
    struct md_debugger *d=md_debugger_create(&host); assert(d);
    struct user_pt_regs regs={.regs={[8]=SYS_wait4,[0]=(unsigned long)-1,[2]=WNOHANG|__WALL}};
    wait_test_hidden=0; wait_test_last=2;
    assert(!md_debugger_call(d,123,&regs));
    wait_test_hidden=1; wait_test_last=0;
    assert(md_debugger_call(d,123,&regs) && wait_test_result==-ECHILD);
    for (unsigned eligible=0;eligible<2;++eligible) {
        wait_test_last=2; wait_test_done=0;
        assert(md_debugger_call(d,123,&regs));
        assert(wait_test_action==MD_DEBUG_WAIT_PROBE && wait_test_probe.regs[0]==1);
        md_debugger_wait_complete(d,123,-ECHILD,NULL);
        assert(!wait_test_done && wait_test_action==MD_DEBUG_WAIT_REPROBE && wait_test_probe.regs[0]==2);
        md_debugger_wait_complete(d,123,eligible ? 0 : -ECHILD,NULL);
        assert(wait_test_done==1 && wait_test_result==(eligible ? 0 : -ECHILD));
    }
    regs.regs[2]=__WALL; wait_test_last=1; wait_test_done=0;
    assert(md_debugger_call(d,123,&regs));
    md_debugger_wait_complete(d,123,0,NULL);
    assert(!wait_test_done && wait_test_action==MD_DEBUG_WAIT_PARK);
    md_debugger_wait_resumed(d,123,0);
    assert(wait_test_action==MD_DEBUG_WAIT_REPROBE && wait_test_probe.regs[0]==1);
    md_debugger_wait_complete(d,123,1,NULL);
    assert(wait_test_done==1 && wait_test_result==1);
    regs=(struct user_pt_regs){.regs={[8]=SYS_waitid,[0]=P_ALL,[3]=WEXITED|WNOWAIT|__WALL}};
    wait_test_done=0;
    assert(md_debugger_call(d,123,&regs));
    siginfo_t info={.si_signo=SIGCHLD,.si_pid=1,.si_code=CLD_EXITED};
    md_debugger_wait_complete(d,123,0,&info);
    assert(wait_test_done==1 && !wait_test_result);
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
    for (unsigned retry=0;retry<2;++retry) {
        debugger_wait(t.pid,&regs,retry ? MD_DEBUG_WAIT_REPROBE : MD_DEBUG_WAIT_PROBE);
        assert(t.phase==DEBUG_WAIT_BUFFER && t.debug_wait_output);
        assert(!t.debug_wait_output->buffer.pool);
        assert(!memcmp(&t.debug_wait_output->registers,&regs,sizeof(regs)));
        assert(t.debug_wait_output->action==(retry ? MD_DEBUG_WAIT_REPROBE : MD_DEBUG_WAIT_PROBE));
        debugger_wait_release(&t);
        assert(!t.debug_wait_output && !t.retries[0].node.list && !t.retries[1].node.list);
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
    t.phase=SIGNAL_CONTROL; t.signal_control=&owner;
    uint64_t mask=UINT64_C(1)<<(SIGUSR1-1);
    unsigned char scratch[sizeof(struct md_task_copy_scratch)+sizeof(siginfo_t)];
    struct md_signal_wait_exchange page;
    struct user_pt_regs regs={.pc=0x3004,.regs={[8]=SYS_rt_sigtimedwait,[0]=(uintptr_t)&mask,[3]=8}};
    assert(md_task_signal_wait_begin(&owner.control,parked_checkpoint_trace,t.pid,&regs,
        0x1000,0x1004,NULL,&t.host_signal_blocked,NULL,NULL,scratch,(uintptr_t)scratch,sizeof(scratch))
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
        struct thread t={.pid=123,.kernel_stopped=1,.signal_shielded=1};
        if (owner<2) {
            t.phase=SIGNAL_CONTROL; t.signal_control=&signal;
            signal.control.invoking=owner==0; signal.control.copying=owner==1;
        } else if (owner==2) {
            t.phase=GUEST_NOTIFICATION; t.notification=&notification;
        } else {
            t.phase=FILTER_CAPTURE; t.filter_install=&filter;
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
    struct thread t={.pid=123,.entered=1,.host_signals=1,.debug_call_active=1};
    struct user_pt_regs regs={.pc=0x12345678,.regs={[8]=SYS_setresgid,[0]=81}};
    debug_host_signal=(siginfo_t){.si_signo=SIGSYS,.si_code=SYS_SECCOMP};
    debug_host_signal.si_arch=AUDIT_ARCH_AARCH64;
    debug_host_signal.si_syscall=SYS_setresgid;
    debug_host_signal.si_call_addr=(void *)(uintptr_t)regs.pc;
    checkpoints.trace=debug_host_trace; debug_host_scans=0;
    assert(debug_host_policy_pending(&t,&regs)==1 && debug_host_scans==1);
    t.host_signals=0; assert(!debug_host_policy_pending(&t,&regs) && debug_host_scans==1);
    t.host_signals=1; t.debug_call_active=0;
    assert(!debug_host_policy_pending(&t,&regs) && debug_host_scans==1);
    t.debug_call_active=1;
    ++regs.pc; assert(!debug_host_policy_pending(&t,&regs)); --regs.pc;
    debug_host_signal.si_code=SI_QUEUE; assert(!debug_host_policy_pending(&t,&regs));
    debug_host_signal.si_code=SYS_SECCOMP;
    const uint32_t actions[]={SECCOMP_RET_ALLOW,SECCOMP_RET_LOG,SECCOMP_RET_ERRNO|EACCES,
        SECCOMP_RET_TRACE|17,SECCOMP_RET_USER_NOTIF,SECCOMP_RET_TRAP|19,SECCOMP_RET_KILL_PROCESS};
    for (unsigned i=0;i<sizeof(actions)/sizeof(*actions);++i) {
        struct sock_filter code[]={BPF_STMT(BPF_RET|BPF_K,actions[i])};
        struct sock_fprog program={1,code};
        assert(!md_seccomp_policy_capture(NULL,&program,&t.policy));
        assert(debug_host_policy_pending(&t,&regs)==(i<5));
        md_seccomp_policy_release(t.policy); t.policy=NULL;
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
    struct thread t={.pid=123,.signal_pending=peer};
    int old_collection=pid_collection_pending;
    checkpoints.trace=pending_child_trace;
    int stopped=(PTRACE_EVENT_STOP<<16) | (SIGTRAP<<8) | 0x7f;
    pending_child_gone=1;
    child_signals_observe(&t,stopped);
    assert(md_signal_pending_child_dirty(peer));
    pending_child_gone=0;
    child_signals_observe(&t,stopped);
    assert(!md_signal_pending_child_dirty(peer) && pid_collection_pending);
    checkpoints.trace=NULL; pid_collection_pending=old_collection;
    md_signal_pending_release(peer); md_signal_pending_release(child);
    puts("PASS pending signal ownership: shared/private scope, first-info coalescing, fork, exit and consumption tokens");
}

static int external_signal_code;
static uint64_t shield_test_mask;
static long shield_test_trace(int request,pid_t pid,void *address,void *data) {
    assert(request==PTRACE_SETSIGMASK && pid==123 && (uintptr_t)address==sizeof(uint64_t));
    shield_test_mask=*(uint64_t *)data;
    return 0;
}
static void signal_shield_ownership(void) {
    struct thread t={.pid=123,.mask=MD_SIGNAL_SYS_BIT};
    checkpoints.trace=shield_test_trace;
    for (int app=0;app<2;++app) {
        t.host_signals=app;
        assert(shield(&t) && t.signal_shielded);
        assert(!!(shield_test_mask&MD_SIGNAL_SYS_BIT)==!app);
        assert(shield_test_mask&(UINT64_C(1)<<(SIGUSR1-1)));
        assert(restore_mask(&t) && !t.signal_shielded && shield_test_mask==t.mask);
    }
    checkpoints.trace=NULL;
    puts("PASS signal shield: native pending SIGSYS retained, app gate available, guest mask restored");
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
        struct thread t={.pid=123,.phase=IDLE,.kernel_stopped=1,.host_signals=host,
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
        assert(t.metadata_restart->stage==METADATA_WAIT && t.signal_shielded);
        assert(metadata_restart_event(&t,status) && metadata_replay_step==10);
        assert(!t.metadata_restart && !t.broker_event.held && !t.signal_shielded
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
    assert(!md_signal_delivery_hold(&t.signal_delivery,&pending));
    t.broker_event.held=t.broker_event.has_info=1;
    uintptr_t gate=abi.raw_gate; abi.raw_gate=(uintptr_t)test_wait_svc+4;
    assert(shield(&t));
    metadata_restart_enter(&t);
    while (waitpid(pid,&status,0)==pid && WIFSTOPPED(status)) {
        t.kernel_stopped=1; t.interrupt_pending=0;
        md_signal_delivery_checkpoint(&t.signal_delivery,status);
        if ((unsigned)status>>16==PTRACE_EVENT_STOP) { resume(pid,0); continue; }
        if (metadata_restart_event(&t,status)) continue;
        int signal=WSTOPSIG(status),deliver=signal;
        int owned=md_signal_delivery_event(&t.signal_delivery,pid,signal,&deliver);
        assert(owned>=0);
        if (signal==SIGSYS) {
            struct user_pt_regs regs; assert(registers(pid,&regs));
            assert(regs.pc==return_pc && regs.regs[0]==(unsigned long)-3);
            siginfo_t info; assert(!ptrace(PTRACE_GETSIGINFO,pid,NULL,&info));
            assert(!md_signal_delivery_before_handler(&t.signal_delivery,pid,&info));
        }
        resume(pid,deliver);
    }
    assert(WIFEXITED(status) && !WEXITSTATUS(status));
    abi.raw_gate=gate; md_signal_delivery_clear(&t.signal_delivery); md_memory_release(t.memory);
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
    struct thread t={.pid=123,.host_signals=1,.entered=1,.host_signal_blocked=1,.signal_pending=pending};
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
    struct thread t={.pid=123,.phase=IDLE,.entered=1,.signal_actions=md_signal_actions_new()};
    assert(t.signal_actions);
    struct md_kernel_action action={.handler=0x5678};
    assert(!md_signal_actions_commit(t.signal_actions,SIGSYS,&action));
    checkpoints.trace=policy_signal_trace;
    for (unsigned cookie=19;cookie<21;++cookie) {
        struct sock_filter code[]={BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRAP|cookie)};
        struct sock_fprog program={1,code};
        assert(!md_seccomp_policy_capture(NULL,&program,&t.policy));
        struct md_task_signal_stop stop;
        md_task_signal_stop_init(&stop,native_trace);
        policy_info_reads=policy_register_reads=policy_info_writes=0;
        t.host_signals=0;
        assert(!host_policy_signal(&t,&stop));
        assert(!policy_info_reads && !policy_register_reads && !policy_info_writes);
        t.host_signals=1;
        assert(signal_observe(&t,&stop,1));
        assert(!host_policy_signal(&t,&stop) && stop.info.si_errno==(int)cookie);
        assert(signal_observe(&t,&stop,1));
        assert(signal_replace_info(&t,&stop,&stop.info));
        assert(policy_info_reads==1 && policy_register_reads==1 && policy_info_writes==(cookie!=19));
        md_seccomp_policy_release(t.policy); t.policy=NULL;
    }
    checkpoints.trace=checkpoint_native_trace;
    md_signal_actions_release(t.signal_actions);
    puts("PASS policy signal observation: one native snapshot, exact changed siginfo, no host gate for shell");
}
static void trace_policy_admission(void) {
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_newfstatat,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_SIGNAL),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    struct thread t={.host_signals=1,.entered=1,.phase=IDLE};
    assert(!md_seccomp_policy_capture(NULL,&program,&t.policy));
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
    t.phase=IDLE; t.host_signals=0; assert(guest_trace_rejected(&t,&call));
    t.host_signals=1; t.entered=0; assert(!guest_trace_rejected(&t,&call));
    md_seccomp_policy_release(t.policy);
    puts("PASS original TRACE admission: notification calls, selector collision and owned-gate isolation");
}

static void filter_result(struct thread *caller,long result) {
    struct user_pt_regs regs=caller->original;
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
    caller.original.regs[8]=SYS_seccomp;
    caller.original.regs[0]=SECCOMP_SET_MODE_FILTER;
    caller.original.regs[1]=SECCOMP_FILTER_FLAG_TSYNC;
    filter_result(&caller,2);
    assert(!caller.application_filter && !peer.application_filter);
    filter_result(&caller,-EINVAL);
    assert(!caller.application_filter && !peer.application_filter);
    filter_result(&caller,0);
    assert(caller.application_filter && peer.application_filter);
    assert(!outside.application_filter && !retired.application_filter);
    caller.application_filter=peer.application_filter=0;
    caller.original.regs[1]=SECCOMP_FILTER_FLAG_NEW_LISTENER;
    filter_result(&caller,5);
    assert(caller.application_filter && !peer.application_filter);
    caller.application_filter=0;
    filter_result(&caller,0);
    assert(caller.application_filter && !peer.application_filter);
    caller.application_filter=0; caller.entered=0;
    filter_result(&caller,5); assert(!caller.application_filter);
    caller.entered=1; caller.original.regs[8]=SYS_prctl;
    caller.original.regs[0]=PR_SET_SECCOMP; caller.original.regs[1]=SECCOMP_MODE_STRICT;
    filter_result(&caller,0); assert(!caller.application_filter);
    caller.original.regs[1]=SECCOMP_MODE_FILTER;
    filter_result(&caller,0); assert(caller.application_filter && !peer.application_filter);
    struct md_interception_buffers *pool=NULL;
    assert(!md_buffers_create_readonly(1,sizeof(struct md_seccomp_image),&pool));
    const long outcomes[]={-EINVAL,23,0};
    for (unsigned i=0;i<sizeof(outcomes)/sizeof(*outcomes);++i) {
        caller.application_filter=peer.application_filter=0;
        caller.original.regs[8]=SYS_seccomp;
        caller.original.regs[0]=SECCOMP_SET_MODE_FILTER;
        caller.original.regs[1]=SECCOMP_FILTER_FLAG_TSYNC;
        caller.filter_install=calloc(1,sizeof(*caller.filter_install)); assert(caller.filter_install);
        struct sock_filter code[]={BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES)};
        struct sock_fprog program={1,code};
        assert(!md_seccomp_capture_prepare(&caller.filter_install->capture,pool,0x100000,
            NULL,&program,NULL,0,0,NULL));
        policy_admission_retain(&peer,1);
        filter_result(&caller,outcomes[i]);
        assert(!caller.filter_install);
        assert(!!caller.policy==(outcomes[i]==0) && caller.policy==peer.policy);
        assert(!outside.policy && !retired.policy);
        uint32_t admitted;
        assert(peer.admission_policy_valid && peer.admission_policy_pending);
        assert(!md_seccomp_policy_eval(admission_policy(&peer),&(struct seccomp_data){.nr=SYS_mount},&admitted));
        assert(admitted==SECCOMP_RET_ALLOW);
        md_seccomp_policy_release(peer.admission_policy); peer.admission_policy=NULL;
        peer.admission_policy_valid=peer.admission_policy_pending=0;
        if (caller.policy) {
            uint32_t decision;
            assert(!md_seccomp_policy_eval(caller.policy,&(struct seccomp_data){.nr=SYS_mount},&decision));
            assert(decision==(SECCOMP_RET_ERRNO|EACCES));
            md_seccomp_policy_replace(&caller.policy,NULL);
            md_seccomp_policy_replace(&peer.policy,NULL);
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
        fd_begin(&t,&registers,MD_INTERCEPT_FD);
        assert(md_resource_order_state(&t.fd_policy_order)==MD_RESOURCE_ORDER_QUEUED);
        assert(!t.fd_order.table && !debugger_at_boundary(&t) && !entry_fence_boundary(&t));
        assert(!memcmp(&t.fd_registers,&registers,sizeof(registers)));
        fd_discard(&t);
        assert(!t.fd_policy_order.queue && debugger_at_boundary(&t));
        assert(!md_resource_order_complete(&install));
        md_fd_origins_release(t.resources.files); md_memory_release(t.memory);
    }
    puts("PASS filter/birth ordering: clone, clone3 and exec wait before FD ownership; cancellation releases the exact turn");
}
static struct md_resource_order filter_replay_waiter;
static long filter_replay_trace(int request,pid_t pid,void *address,void *data) {
    assert(request==PTRACE_SETREGSET && pid==123 && (uintptr_t)address==NT_PRSTATUS && data);
    assert(!find_thread(pid)->filter_install);
    assert(md_resource_order_state(&filter_replay_waiter)==MD_RESOURCE_ORDER_READY);
    /* Stop at the first kernel publication, before restoring the signal mask. */
    errno=ESRCH; return -1;
}
static void filter_replay_ownership(void) {
    const long outcomes[]={0,-ENOMEM,-EINVAL};
    for (unsigned i=0;i<sizeof(outcomes)/sizeof(*outcomes);++i) {
        struct thread t={.pid=123,.phase=DISPATCHING,.filter_preflight=1,.memory=md_memory_new()};
        assert(t.memory);
        t.original.regs[8]=SYS_seccomp; t.original.pc=0x1000;
        t.filter_install=calloc(1,sizeof(*t.filter_install)); assert(t.filter_install);
        t.filter_install->source=-1;
        assert(md_memory_order_begin(t.memory,&t.filter_install->memory,NULL)==1);
        assert(!md_resource_order_start(&t.filter_install->memory));
        assert(!md_memory_order_begin(t.memory,&filter_replay_waiter,NULL));
        checkpoints.trace=filter_replay_trace; thread_index[t.pid%THREAD_BUCKETS]=&t;
        dispatch_complete(&t,outcomes[i]);
        assert(t.resume_lost && !t.filter_install && !t.filter_preflight && t.registration_revoked);
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
        caller.original.regs[8]=SYS_seccomp;
        caller.original.regs[0]=SECCOMP_SET_MODE_FILTER;
        caller.original.regs[1]=SECCOMP_FILTER_FLAG_NEW_LISTENER;
        caller.resources.files=md_fd_origins_new(); assert(caller.resources.files);
        caller.filter_install=calloc(1,sizeof(*caller.filter_install)); assert(caller.filter_install);
        caller.filter_install->virtual_listener=1; caller.filter_install->source=-1;
        struct md_interception_buffers *pool=NULL;
        assert(!md_buffers_create_readonly(1,sizeof(struct md_seccomp_image),&pool));
        struct sock_filter code[]={BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_USER_NOTIF)};
        struct sock_fprog program={1,code};
        assert(!md_seccomp_capture_prepare(&caller.filter_install->capture,pool,0x100000,
            NULL,&program,NULL,0,0,NULL));
        struct md_fd_order previous={0};
        assert(md_fd_order_begin(caller.resources.files,&previous,NULL)==1);
        assert(!md_fd_order_start(&previous));
        assert(filter_listener_prepare(&caller)==-EAGAIN);
        assert(caller.filter_install->capture.policy && !caller.filter_install->listener.table);
        assert(!md_fd_order_complete(&previous));
        assert(!filter_listener_prepare(&caller));
        assert(!filter_listener_prepare(&caller));
        assert(md_fd_order_state(&caller.filter_install->files)==MD_RESOURCE_ORDER_RUNNING);
        if (outcomes[i]>=0) {
            caller.filter_install->loan->number=outcomes[i];
            caller.filter_install->admitted=1;
            filter_result(&caller,outcomes[i]);
        } else filter_install_discard(&caller);
        assert(!caller.filter_install);
        struct md_fd_object *object=md_fd_origins_object(caller.resources.files,(int)outcomes[i],NULL);
        assert(!!object==(outcomes[i]>=0));
        if (object) {
            assert(md_seccomp_listener_policy(object)==caller.policy);
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
            struct md_fd_object *other=md_seccomp_listener_new(caller.policy); assert(other && other!=object);
            assert(!md_fd_origins_prepare_object(destination,other,FD_CLOEXEC,&update));
            md_fd_origins_finish(&update,number);
            received=md_fd_origins_object(destination,number,NULL);
            assert(received==other && received!=object);
            md_fd_object_release(received); md_fd_object_release(other);
            md_fd_origins_release(destination);
            md_seccomp_policy_replace(&caller.policy,NULL);
            uint32_t action;
            assert(!md_seccomp_policy_eval(md_seccomp_listener_policy(object),&(struct seccomp_data){0},&action)
                && action==SECCOMP_RET_USER_NOTIF);
        }
        md_fd_object_release(object); md_seccomp_policy_replace(&caller.policy,NULL);
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
    struct thread t={.pid=123,.memory_arguments_only=1};
    thread_index[t.pid%THREAD_BUCKETS]=&t;
    checkpoints.trace=memory_context_trace;
    memory_saved=md_task_registers_entry(0x1004,0x8000,UINT64_C(1)<<12);
    for (unsigned i=0;i<31;++i) memory_saved.regs[i]=0x100+i;
    memory_saved.regs[8]=SYS_mmap;
    struct user_pt_regs operands={.pc=memory_saved.pc,.sp=memory_saved.sp};
    assert(memory_context(&t,&operands));
    assert(!t.memory_arguments_only && memory_reads==1);
    assert(!memcmp(&operands,&memory_saved,sizeof(operands)));
    assert(!memcmp(&t.original,&memory_saved,sizeof(t.original)));
    assert(memory_context(&t,&operands) && memory_reads==1);
    t.memory_arguments_only=1; memory_read_error=1;
    assert(!memory_context(&t,&operands) && t.resume_lost && t.memory_arguments_only);
    assert(memory_reads==2 && !memcmp(&operands,&memory_saved,sizeof(operands)));
    memory_read_error=0; checkpoints.trace=checkpoint_native_trace;
    thread_index[t.pid%THREAD_BUCKETS]=NULL;
    puts("PASS memory observation: lazy complete GPR capture, processor controls and lost-task cancellation");
}
static void entry_revocation_lost_stop(void) {
    struct thread t={.pid=INT_MAX,.memory=md_memory_new()}; assert(t.memory);
    struct md_svc_registry *r=md_svc_registry_new(0x1000000,0x4000); assert(r);
    assert(!md_memory_svc_set(t.memory,r));
    assert(!svc_revoke_entry(&t) && t.resume_lost && r->disabled && r->revoke_pending);
    t.resume_lost=0;
    struct user_pt_regs regs={.regs={[8]=SYS_seccomp,[1]=SECCOMP_FILTER_FLAG_TSYNC}};
    assert(!filter_revoke_entry(&t,&regs) && t.resume_lost && r->revoke_pending);
    assert(!t.filter_install && !t.cooperative_blocked);
    md_memory_release(t.memory);
    puts("PASS entry revocation: lost stop retains mm retry without committing a filter");
}
static void memory_observation_routes(void) {
    struct thread t={.pid=123,.memory=md_memory_new()}; assert(t.memory);
    struct md_svc_registry *r=md_svc_registry_new(0x1000000,0x4000); assert(r);
    assert(!md_memory_svc_set(t.memory,r));
    r->pool_count=1; r->pools[0]=(struct md_svc_code_pool){.address=0x3000,.used=8};
    r->count=1; r->sites[0]=(struct md_svc_site){.direct={.svc=0x2000,.gate=0x3000,.number=SYS_mmap},
        .kernel_alias=0x3000,.state=MD_SVC_PUBLISHED};
    struct ptrace_syscall_info info={.arch=AUDIT_ARCH_AARCH64,.instruction_pointer=0x2004,
        .stack_pointer=0x800000,.seccomp={.nr=SYS_mmap,.ret_data=MD_INTERCEPT_MEMORY,
            .args={0x1000,0x2000,PROT_READ,MAP_PRIVATE,19,0x3000}}};
    struct user_pt_regs observed;
    int alias;
    assert(memory_observation(&t,&info,&observed,&alias) && !alias);
    assert(observed.pc==info.instruction_pointer && observed.sp==info.stack_pointer
        && observed.regs[8]==SYS_mmap && !memcmp(observed.regs,info.seccomp.args,sizeof(info.seccomp.args)));
    info.instruction_pointer=0x3004;
    assert(memory_observation(&t,&info,&observed,&alias) && alias==1);
    assert(observed.pc==0x3004 && observed.regs[4]==19);
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
    t.application_filter=1;
    assert(!memory_observation(&t,&info,&observed,&alias)); t.application_filter=0;
    t.cooperative_blocked=1;
    assert(!memory_observation(&t,&info,&observed,&alias)); t.cooperative_blocked=0;
    t.debug_options=1;
    assert(!memory_observation(&t,&info,&observed,&alias)); t.debug_options=0;
    t.entry_fence_result=-EPERM;
    assert(!memory_observation(&t,&info,&observed,&alias)); t.entry_fence_result=0;
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
    struct md_svc_registry *r=md_svc_registry_new(0x1000000,0x4000); assert(r);
    assert(!md_memory_svc_set(t.memory,r));
    r->pool_count=1; r->pools[0]=(struct md_svc_code_pool){.address=0x3000,.used=8};
    assert(machine_context(&t,&info) && machine_discovery(&t));
    t.notification_continued=1;
    assert(!machine_discovery(&t));
    t.notification_continued=0; t.admission_policy_valid=1;
    assert(!machine_discovery(&t));
    t.admission_policy_valid=0;
    for (unsigned i=0;i<4;++i) {
        t.application_filter=i==0; t.cooperative_blocked=i==1;
        t.debug_options=i==2; r->disabled=i==3;
        assert(!machine_discovery(&t) && !machine_context(&t,&info));
        info.instruction_pointer=0x3004;
        assert(machine_context(&t,&info));
        info.instruction_pointer=0x2004; info.stack_pointer=r->frames+16;
        assert(machine_context(&t,&info));
        info.stack_pointer=0x800000;
        assert(machine_context(&t,NULL));
    }
    t.application_filter=t.cooperative_blocked=0; t.debug_options=0; r->disabled=0;
    t.entered=0;
    assert(!machine_discovery(&t) && !machine_context(&t,&info));
    t.entered=1; info.seccomp.nr=SYS_sched_yield;
    assert(!machine_context(&t,&info));
    info.instruction_pointer=0x3004;
    assert(machine_context(&t,&info));
    t.phase=DISPATCHING;
    assert(!machine_context(&t,&info) && !machine_context(&t,NULL));
    t.phase=IDLE; t.application_filter=1;
    struct sock_filter code[]={BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
    struct sock_fprog program={1,code};
    assert(!md_seccomp_policy_capture(NULL,&program,&t.policy));
    assert(!machine_discovery(&t));
    md_seccomp_policy_bind_entry(t.policy,r->image);
    assert(!machine_discovery(&t));
    struct md_interception_buffers *pool=NULL;
    assert(!md_buffers_create_readonly(1,128,&pool));
    assert(!md_memory_snapshot_publish(t.memory,md_seccomp_policy_identity(t.policy),pool,0x200000));
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
    md_buffers_release(pool); md_seccomp_policy_release(t.policy);
    md_memory_release(t.memory);
    puts("PASS machine context: lazy discovery capture, revoked entry recovery and task-owned dispatch");
    puts("PASS policy entry admission: published view, exact image, fork/exec and mapping mutation scope");
}
static void processor_controls(void) {
    const uint64_t controls[]={0,UINT64_C(1)<<12,UINT64_C(1)<<24,(UINT64_C(1)<<12)|(UINT64_C(1)<<24)};
    for (unsigned i=0;i<sizeof(controls)/sizeof(*controls);++i) {
        struct user_pt_regs regs=md_task_registers_entry(0x1000,0x2000,controls[i]|UINT64_C(0xf0000000));
        assert(regs.pc==0x1000 && regs.sp==0x2000 && regs.pstate==controls[i]);
        for (unsigned j=0;j<sizeof(regs.regs)/sizeof(*regs.regs);++j) assert(!regs.regs[j]);
    }
    puts("PASS fresh continuation: operands/NZCV cleared, processor controls retained");
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
    t.resources.files=files; t.original.regs[0]=5; t.original.regs[8]=SYS_epoll_pwait;
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
            .socket_waking=1,.signal_shielded=1,.readiness=&owner};
        readiness_before_call=(struct user_pt_regs){.pc=abi.task_call};
        readiness_before_call.regs[8]=numbers[i];
        memcpy(readiness_before_call.regs,owner.native_call.value,sizeof(owner.native_call.value));
        readiness_entry_reads=readiness_entry_resumes=0;
        thread_index[t.pid%THREAD_BUCKETS]=&t; checkpoints.trace=readiness_entry_trace;
        int status=(PTRACE_EVENT_STOP<<16)|(SIGTRAP<<8)|0x7f;
        assert(readiness_native_event(&t,status));
        assert(readiness_entry_reads==1 && readiness_entry_resumes==1);
        assert(t.phase==EPOLL_CALL && !t.socket_waking && !t.kernel_stopped && t.signal_shielded);
        assert(!owner.done && !owner.finishing && !owner.parked && owner.evicting==(int)evict);
        assert(!readiness_native_event(&t,(SIGUSR1<<8)|0x7f));
        assert(readiness_entry_reads==1 && readiness_entry_resumes==1);
        checkpoints.trace=checkpoint_native_trace; thread_index[t.pid%THREAD_BUCKETS]=NULL;
    }
    abi.task_call=call; abi.task_return=returned;
    puts("PASS readiness entry interrupt: resume exact call, retain eviction and mask, consume only service wake");
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
    const enum phase parked[]={WATCH_READ,IPC_WAITING,SOCKET_WAITING,FILE_WAITING,
        CONTROL_WAITING,CLOSE_WAITING,EPOLL_WAIT};
    for (unsigned i=0;i<sizeof(parked)/sizeof(*parked);++i) {
        t.phase=parked[i];
        assert(task_parked(&t) && freezer_parked(&t) && task_action_borrowable(&t,NULL));
        t.signal_return_pending=1;
        assert(!task_action_borrowable(&t,NULL));
        t.signal_return_pending=0;
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
    t.signal_return_pending=1;
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
    assert(!md_signal_delivery_hold(&t.signal_delivery,&signal));
    t.ready=0; t.kernel_stopped=1;
    thread_index[t.pid%THREAD_BUCKETS]=&t; checkpoints.trace=readiness_entry_trace;
    readiness_entry_reads=readiness_entry_resumes=0;
    resume(t.pid,0);
    assert(readiness_entry_resumes==1 && !readiness_entry_reads && !t.kernel_stopped);
    assert(t.signal_return_pending && !t.signal_delivery.active
        && md_signal_delivery_pending(&t.signal_delivery));
    checkpoints.trace=checkpoint_native_trace; thread_index[t.pid%THREAD_BUCKETS]=NULL;
    md_signal_delivery_clear(&t.signal_delivery); md_fd_origins_release(files);
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
            t.phase=phases[phase]; t.robust_registering=state==0;
            t.robust_terminal=state;
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
            t.selector_exit=456; t.kernel_stopped=1;
            thread_index[t.pid%THREAD_BUCKETS]=&t;
            assert(!task_completion_available(t.pid));
            assert(!task_continuation_available(&t));
            retry_wait(&t,RETRY_TASK_ACTION);
            retry_dispatch(0);
            assert(t.selector_exit==456 && !t.resume_lost && retained_task_held(&t));
            assert(t.retries[0].mask==RETRY_TASK_ACTION && t.retries[0].node.list);
            retry_clear(&t,RETRY_TASK_ACTION);
            thread_index[t.pid%THREAD_BUCKETS]=NULL;
        }
    t.robust_registering=t.robust_terminal=0;
    assert(task_continuation_available(&t));
    md_fd_origins_release(files);
    puts("PASS retained continuation custody: registration, terminal cleanup and replay exclude every borrowed actor role");
}
static unsigned readiness_interrupted_step;
static long readiness_interrupted_trace(int request,pid_t pid,void *address,void *data) {
    assert(pid==123);
    struct thread *t=find_thread(pid); assert(t);
    switch (readiness_interrupted_step++) {
    case 0:
        assert(request==PTRACE_GETREGSET && (uintptr_t)address==NT_PRSTATUS);
        *(struct user_pt_regs *)((struct iovec *)data)->iov_base=
            (struct user_pt_regs){.regs={(uint64_t)-514}};
        break;
    case 1: {
        assert(request==PTRACE_SETREGSET && (uintptr_t)address==NT_PRSTATUS);
        struct user_pt_regs expected=t->original; expected.regs[0]=(uint64_t)-EINTR;
        assert(!memcmp(((struct iovec *)data)->iov_base,&expected,sizeof(expected)));
        assert(t->signal_return_pending && !t->readiness);
        break;
    }
    case 2:
        assert(request==PTRACE_SYSCALL && !address && !data);
        assert(t->signal_return_pending && t->phase==IDLE && !t->signal_shielded);
        break;
    default: assert(!"unexpected mask change or injected call after interrupted wait");
    }
    return 0;
}
static void readiness_parked_signal_return(void) {
    struct thread t={.pid=123,.phase=SOCKET_WAITING,.kernel_stopped=1,
        .memory=md_memory_new(),.readiness=calloc(1,sizeof(struct readiness_owner))};
    assert(t.memory && t.readiness);
    t.original=(struct user_pt_regs){.pc=0x1004,.regs={7,0x2000,3,4000,0,8}};
    t.original.regs[8]=SYS_epoll_pwait;
    readiness_interrupted_step=0;
    thread_index[t.pid%THREAD_BUCKETS]=&t; checkpoints.trace=readiness_interrupted_trace;
    socket_wait_complete(&t);
    assert(readiness_interrupted_step==3 && !t.kernel_stopped && t.signal_return_pending);
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
    assert(t.phase==WAIT_CANCEL_EXIT && t.wait_resume==EPOLL_CALL && !owner.parked);
    assert(readiness_cancel_step==4);
    uint64_t events;
    assert(read(fd_ready,&events,sizeof(events))==-1 && errno==EAGAIN);
    /* A pending signal/interrupt cannot publish a still-entering wait. */
    assert(!readiness_native_event(&t,(PTRACE_EVENT_STOP<<16)|(SIGTRAP<<8)|0x7f));
    assert(!owner.parked && readiness_cancel_step==4);
    t.kernel_stopped=1;
    assert(readiness_native_event(&t,status));
    assert(owner.parked && t.phase==DISPATCHING && t.wait_resume==IDLE && readiness_cancel_step==5);
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
    assert(t.phase==WAIT_CANCEL_EXIT && t.wait_resume==EPOLL_CALL && owner.finishing && !owner.done);
    readiness_cancel_error=owner.finish_result; assert(readiness_cancel_error<0);
    assert(readiness_cancel_step==4 && !owner.parked);
    assert(read(fd_ready,&events,sizeof(events))==-1 && errno==EAGAIN);
    t.kernel_stopped=1;
    assert(readiness_native_event(&t,status));
    assert(owner.done && !owner.parked && t.phase==DISPATCHING && t.wait_resume==IDLE);
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
        struct thread t={.pid=123,.fd_job=MD_FD_DUPLICATE_BEGIN};
        t.resources.files=files; t.original.regs[0]=5; t.original.regs[8]=SYS_dup3;
        t.fd_registers.regs[1]=view.generation;
        assert(md_fd_order_begin(files,&t.fd_order,NULL)==1);
        assert(!md_fd_order_start(&t.fd_order));
        assert(!md_fd_origins_validate(&t.fd_order,view.generation));
        assert(!md_fd_origins_close_range(files,10,10,0));
        assert(md_fd_origins_validate(&t.fd_order,view.generation)==-EAGAIN);
        thread_index[t.pid%THREAD_BUCKETS]=&t;
        checkpoints.trace=duplicate_return_trace;
        duplicate_reply=owned ? 0 : -EAGAIN;
        if (owned) fd_loan_closed(&t,0);
        else fd_start(&t);
        checkpoints.trace=checkpoint_native_trace;
        thread_index[t.pid%THREAD_BUCKETS]=NULL;
        assert(t.resume_lost && !t.retries[0].mask && !t.retries[1].mask);
        if (owned) {
            assert(t.fd_job==MD_FD_DUPLICATE_BEGIN);
            assert(!md_fd_origins_validate(&t.fd_order,t.fd_registers.regs[1]));
            assert(!md_fd_order_complete(&t.fd_order));
        } else assert(!t.fd_job && !t.fd_order.table);
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
        t.fd_registers.regs[8]=SYS_close; t.fd_registers.regs[0]=14;
        assert(md_fd_order_begin(files,&t.fd_order,NULL)==1);
        assert(!md_fd_order_start(&t.fd_order));
        assert(!fd_loans_ready(&t));
        struct task_action *action=t.action;
        assert(action && loan_retirements==action && task_action_calls==1);
        assert(action->loan==loan && action->loan_table==files && action->number==14);
        assert(action->loan_turn==&t.fd_order && action->complete==fd_loan_closed);
        assert(md_fd_order_state(&t.fd_order)==MD_RESOURCE_ORDER_RUNNING);
        assert(!parent_wait.evicting && parent_wait.loans[0].retirement==&parent_retirement);
        assert(!md_fd_origins_close_range(files,14,14,0));
        t.action=NULL; task_action_release(action);
        assert(fd_loans_ready(&t));
        assert(!md_fd_order_complete(&t.fd_order));
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
        t.fd_registers.regs[8]=SYS_close; t.fd_registers.regs[0]=14;
        assert(md_fd_order_begin(t.resources.files,&t.fd_order,NULL)==1);
        assert(!md_fd_order_start(&t.fd_order));
        struct task_action *prior=calloc(1,sizeof(*prior)); assert(prior);
        prior->loan=loan; prior->loan_table=md_fd_origins_pin(home); prior->number=14;
        loan_retire(prior,NULL,NULL,NULL);
        assert(!fd_loans_ready(&t));
        if (copied) {
            assert(t.action && t.action!=prior && task_action_calls==2);
            assert(md_fd_order_state(&t.fd_order)==MD_RESOURCE_ORDER_RUNNING);
            struct task_action *local=t.action; t.action=NULL; task_action_release(local);
            md_fd_origins_release(t.resources.files);
        } else {
            assert(!t.action && task_action_calls==1);
            assert(md_fd_order_state(&prior->files)==MD_RESOURCE_ORDER_READY);
            assert(md_fd_order_state(&t.fd_order)==MD_RESOURCE_ORDER_QUEUED);
            retry_clear(&t,RETRY_FD);
        }
        task_action_release(prior);
        if (md_fd_order_state(&t.fd_order)==MD_RESOURCE_ORDER_READY) assert(!md_fd_order_start(&t.fd_order));
        assert(!md_fd_order_complete(&t.fd_order));
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
        struct thread t={.pid=123,.phase=IDLE,.kernel_stopped=1,.entered=1,.host_signals=1,
            .memory=md_memory_new(),.signal_actions=md_signal_actions_new()};
        assert(t.memory && t.signal_actions);
        signal_delivery_number=sys ? SIGSYS : SIGALRM;
        struct md_kernel_action action={.handler=0x1234,
            .mask=(UINT64_C(1)<<(SIGUSR2-1))|(blocked ? MD_SIGNAL_SYS_BIT : 0),
            .flags=(reset ? SA_RESETHAND : 0)|(nodefer ? SA_NODEFER : 0)};
        assert(!md_signal_actions_commit(t.signal_actions,signal_delivery_number,&action));
        assert(signal_frame_native(&t));
        t.host_signal_blocked=1; assert(!signal_frame_native(&t));
        t.host_signal_blocked=0; t.signal_wait_view.active=1;
        assert(!signal_frame_native(&t));
        t.signal_wait_view.active=0;
        signal_delivery_queries=signal_delivery_resumes=0;
        checkpoints.trace=signal_delivery_trace; thread_index[t.pid%THREAD_BUCKETS]=&t;
        assert(signal_control_delivery(&t,signal_delivery_number,NULL));
        assert(signal_delivery_queries==1 && signal_delivery_resumes==1 && !t.signal_control
            && t.phase==IDLE && !t.kernel_stopped && t.host_signal_blocked==!!(blocked || (sys && !nodefer)));
        assert(!md_signal_actions_read(t.signal_actions,signal_delivery_number,&action));
        assert(action.handler==(reset ? (uintptr_t)SIG_DFL : 0x1234));
        checkpoints.trace=checkpoint_native_trace; thread_index[t.pid%THREAD_BUCKETS]=NULL;
        md_signal_actions_release(t.signal_actions); md_memory_release(t.memory);
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
    for (unsigned blocked=0;blocked<2;++blocked) for (unsigned output=0;output<2;++output)
            for (unsigned input=0;input<2;++input) {
        struct thread t={.pid=123,.phase=IDLE,.kernel_stopped=1,.entered=1,.host_signals=1,
            .host_signal_blocked=blocked,.memory=md_memory_new()};
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
            && t.host_signal_blocked==(int)expected_blocked && !t.signal_control && !t.signal_query_active);
        checkpoints.trace=checkpoint_native_trace; thread_index[t.pid%THREAD_BUCKETS]=NULL;
        md_memory_release(t.memory);
    }
    struct thread pending={.pid=123,.phase=SIGNAL_NATIVE_EXIT,.kernel_stopped=1,
        .entered=1,.host_signals=1,.signal_delivery={.active=1},.memory=md_memory_new()};
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
    struct thread t={.pid=getpid(),.phase=IDLE,.kernel_stopped=1,.entered=1,.host_signals=1,
        .host_signal_blocked=1,.memory=md_memory_new()}; assert(t.memory);
    uint64_t *output=mmap(NULL,getpagesize(),PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(output!=MAP_FAILED);
    thread_index[t.pid%THREAD_BUCKETS]=&t; checkpoints.trace=direct_query_trace;
    for (unsigned fault=0;fault<2;++fault) {
        if (fault) assert(!mprotect(output,getpagesize(),PROT_READ));
        query_register_reads=query_register_writes=query_resumes=0; t.kernel_stopped=1;
        assert(signal_native_call(&t,SYS_rt_sigprocmask,0,(uintptr_t)output,8));
        assert(t.phase==IDLE && !t.signal_query_active && !t.kernel_stopped && t.host_signal_blocked);
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
        struct thread t={.pid=123,.phase=IDLE,.kernel_stopped=1,.entered=1,.host_signals=1,
            .host_signal_blocked=blocked,.memory=md_memory_new(),.signal_actions=md_signal_actions_new()};
        assert(t.memory && t.signal_actions);
        struct md_signal_actions *peer=md_signal_actions_fork(t.signal_actions,1,0); assert(peer);
        uint64_t before=UINT64_C(1)<<(SIGUSR2-1);
        struct md_kernel_action action={.handler=0x1234,.mask=action_bit ? MD_SIGNAL_SYS_BIT : 0};
        assert(!md_signal_actions_commit(t.signal_actions,SIGALRM,&action));
        signal_query_mask=before;
        signal_query_reads=signal_query_writes=signal_query_calls=signal_query_returns=0;
        signal_frame_interrupts=signal_frame_deliveries=0;
        checkpoints.trace=signal_frame_trace; thread_index[t.pid%THREAD_BUCKETS]=&t;
        assert(signal_control_delivery(&t,SIGALRM,NULL));
        assert(t.phase==SIGNAL_ENTRY && t.signal_control && !t.signal_control->scratch.pool
            && signal_query_reads==1 && signal_query_writes==blocked
            && signal_query_mask==(before|(blocked ? MD_SIGNAL_SYS_BIT : 0))
            && signal_frame_interrupts==1 && signal_frame_deliveries==1);
        signal_query_mask|=UINT64_C(1)<<(SIGALRM-1);
        t.kernel_stopped=1;
        assert(signal_control_event(&t,(PTRACE_EVENT_STOP<<16)|(SIGTRAP<<8)|0x7f));
        assert(t.phase==IDLE && !t.signal_control && !t.kernel_stopped
            && t.host_signal_blocked==!!(blocked|action_bit)
            && signal_query_mask==(before|(UINT64_C(1)<<(SIGALRM-1)))
            && signal_query_reads==1 && signal_query_writes==2*blocked
            && !signal_query_calls && signal_query_returns==1);
        checkpoints.trace=checkpoint_native_trace; thread_index[t.pid%THREAD_BUCKETS]=NULL;
        md_signal_actions_release(peer); md_signal_actions_release(t.signal_actions); md_memory_release(t.memory);
    }
    md_debugger_destroy(debugger); debugger=NULL;
    puts("PASS logical signal frame: kernel mask publication, no buffer or register copy, native handler mask restored");
}
static void native_signal_entry(void) {
    for (unsigned reset=0;reset<2;++reset) {
        struct thread t={.pid=123,.phase=SIGNAL_ENTRY,.kernel_stopped=1,
            .memory=md_memory_new(),.signal_actions=md_signal_actions_new()};
        assert(t.memory && t.signal_actions);
        struct signal_control *owner=calloc(1,sizeof(*owner)); assert(owner);
        t.signal_control=owner; owner->started=1; owner->signal=SIGALRM;
        owner->action=(struct md_kernel_action){.handler=0x1234,
            .mask=UINT64_C(1)<<(SIGUSR2-1),.flags=reset ? SA_RESETHAND : 0};
        assert(!md_signal_actions_commit(t.signal_actions,SIGALRM,&owner->action));
        assert(md_memory_order_begin(t.memory,&owner->memory,NULL)==1);
        assert(!md_resource_order_start(&owner->memory));
        fd_expected_resume=PTRACE_CONT; fd_resume_calls=0;
        checkpoints.trace=fd_resume_trace; thread_index[t.pid%THREAD_BUCKETS]=&t;
        assert(signal_control_event(&t,(PTRACE_EVENT_STOP<<16)|(SIGTRAP<<8)|0x7f));
        assert(fd_resume_calls==1 && !t.signal_control && t.phase==IDLE
            && !t.kernel_stopped && !t.host_signal_blocked);
        struct md_kernel_action action;
        assert(!md_signal_actions_read(t.signal_actions,SIGALRM,&action));
        assert(action.handler==(reset ? (uintptr_t)SIG_DFL : 0x1234));
        struct md_resource_order next={0};
        assert(md_memory_order_begin(t.memory,&next,NULL)==1);
        assert(!md_resource_order_start(&next) && !md_resource_order_complete(&next));
        checkpoints.trace=checkpoint_native_trace; thread_index[t.pid%THREAD_BUCKETS]=NULL;
        md_signal_actions_release(t.signal_actions); md_memory_release(t.memory);
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
        struct thread t={.pid=123,.signal_control=state ? &owner : NULL,
            .host_signal_blocked=held==1,.kernel_stopped=held==2,.interrupt_pending=held==3};
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
            .signal_actions=md_signal_actions_new()};
        assert(t.signal_actions);
        struct signal_control *owner=calloc(1,sizeof(*owner)); assert(owner);
        struct md_signal_entry packet={.pending=1};
        t.signal_control=owner; owner->signal=SIGALRM; owner->started=1;
        owner->entry_packet=&packet; owner->entry_thread=&t;
        owner->entry_previous=&signal_entries; signal_entries=owner;
        struct md_kernel_action action={.handler=0x1234,.flags=reset ? SA_RESETHAND : 0};
        assert(!md_signal_actions_commit(t.signal_actions,SIGALRM,&action));
        signal_entries_progress();
        assert(t.signal_control==owner && t.phase==SIGNAL_ENTRY);
        /* The handler acknowledges entry after the global scan but before its
         * syscall is admitted. No ptrace stop is needed to retire the packet. */
        atomic_store_explicit(&packet.pending,0,memory_order_release);
        owner->returning=1; assert(!signal_entry_committed(&t)); owner->returning=0;
        assert(signal_entry_committed(&t) && !t.signal_control && t.phase==IDLE && !signal_entries);
        assert(!signal_entry_committed(&t));
        assert(!md_signal_actions_read(t.signal_actions,SIGALRM,&action));
        assert(action.handler==(reset ? (uintptr_t)SIG_DFL : 0x1234));
        md_signal_actions_release(t.signal_actions);
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
        if (mode==4 && operation) t.debug_options=PTRACE_O_TRACEEXEC;
        if (mode==5) t.application_filter=1;
        /* Abandoning an ordinary origin removes it; it is not an uncertain
         * infrastructure slot. Close remains metadata-free, flags do not. */
        int direct=mode==0 || mode==5 || (!operation && mode==3);
        fd_test_operation=operation; fd_test_mode=mode;
        fd_expected_resume=direct ? PTRACE_CONT : PTRACE_SYSCALL;
        fd_resume_calls=0; checkpoints.trace=fd_resume_trace;
        thread_index[t.pid%THREAD_BUCKETS]=&t;
        if (operation) {
            t.fd_registers.regs[8]=SYS_fcntl; t.fd_registers.regs[0]=5;
            t.fd_registers.regs[1]=(UINT64_C(1)<<32)|F_SETFD;
            t.fd_registers.regs[2]=mode==2 ? 0 : FD_CLOEXEC;
            assert(md_fd_order_begin(files,&t.fd_order,NULL)==1 && !md_fd_order_start(&t.fd_order));
            fd_native_start(&t);
        } else {
            struct ptrace_syscall_info info={.op=PTRACE_SYSCALL_INFO_SECCOMP,.arch=AUDIT_ARCH_AARCH64,
                .seccomp={.nr=SYS_close,.args={5}}};
            assert(fd_close_compact(&t,MD_INTERCEPT_FD,&info));
        }
        assert(fd_resume_calls==1 && !t.resume_lost && t.fd_running==!direct);
        assert(!!t.fd_order.table==!direct);
        if (!direct) fd_finish(&t,-EPERM);
        checkpoints.trace=checkpoint_native_trace;
        thread_index[t.pid%THREAD_BUCKETS]=NULL;
        md_fd_origins_release(peer); md_fd_origins_release(files);
    }
    md_fs_anchor_release(origin);
    puts("PASS native FD retirement: real syscall continuation, metadata-free close, shared/changed/debugged observation, filtered no-op and wide command");
}
static void duplicate_prepared_admission(void) {
    struct thread t={.pid=123,.phase=IDLE,.ready=1};
    t.resources.files=md_fd_origins_new(); assert(t.resources.files);
    t.fd_selector=md_fd_selector_new(); assert(t.fd_selector);
    struct user_pt_regs regs={0}; regs.regs[8]=SYS_fcntl;
    regs.regs[0]=5; regs.regs[1]=F_DUPFD_CLOEXEC;
    assert(!fd_duplicate_prepared(&t,&regs,MD_INTERCEPT_DISPATCH));
    for (unsigned kind=0;kind<MD_FD_SELECTOR_KINDS;++kind)
        if (MD_FD_SANDBOX_SELECTORS&(1U<<kind)) assert(!md_fd_selector_mark(t.fd_selector,kind,-1));
    struct md_fd_object *object=md_descriptor_inode_new(&t.identity,S_IFIFO|0600); assert(object);
    struct md_fd_origin_update update;
    assert(!md_fd_origins_prepare_object(t.resources.files,object,0,&update));
    md_fd_origins_finish(&update,5);
    struct md_fd_origins *peer=md_fd_origins_fork(t.resources.files,1); assert(peer);
    for (unsigned operation=0;operation<4;++operation) for (unsigned success=0;success<2;++success) {
        regs.regs[8]=operation<2 ? SYS_fcntl : operation==2 ? SYS_dup : SYS_dup3;
        regs.regs[1]=operation<2 ? (UINT64_C(1)<<32)|(operation ? F_DUPFD : F_DUPFD_CLOEXEC) : 6;
        regs.regs[2]=operation==3 ? O_CLOEXEC : 0;
        assert(fd_duplicate_prepared(&t,&regs,MD_INTERCEPT_DISPATCH));
        assert(!fd_duplicate_prepared(&t,&regs,MD_INTERCEPT_PROC));
        t.debug_options=PTRACE_O_TRACEEXEC;
        assert(!fd_duplicate_prepared(&t,&regs,MD_INTERCEPT_DISPATCH)); t.debug_options=0;
        t.application_filter=1;
        assert(fd_duplicate_prepared(&t,&regs,MD_INTERCEPT_DISPATCH));
        fd_expected_resume=PTRACE_SYSCALL; fd_resume_calls=0;
        checkpoints.trace=fd_resume_trace; thread_index[t.pid%THREAD_BUCKETS]=&t;
        fd_begin(&t,&regs,MD_INTERCEPT_FD);
        assert(fd_resume_calls==1 && t.fd_running && t.fd_order.table);
        struct md_fd_object *before=md_fd_origins_object(peer,6,NULL); assert(!before);
        fd_finish(&t,success ? 6 : -EPERM);
        assert(!t.fd_running && !t.fd_order.table);
        unsigned flags=0;
        struct md_fd_object *after=md_fd_origins_object(peer,6,&flags);
        assert(after==(success ? object : NULL));
        if (success) assert(flags==((operation==0 || operation==3) ? FD_CLOEXEC : 0));
        md_fd_object_release(after);
        assert(!md_fd_origins_close_range(peer,6,6,0));
        checkpoints.trace=checkpoint_native_trace; thread_index[t.pid%THREAD_BUCKETS]=NULL;
    }
    regs.regs[8]=SYS_fcntl; regs.regs[1]=F_SETFL;
    assert(!fd_duplicate_prepared(&t,&regs,MD_INTERCEPT_DISPATCH));
    md_fd_object_release(object); md_fd_origins_release(peer); md_fd_origins_release(t.resources.files);
    md_fd_selector_release(t.fd_selector);
    puts("PASS prepared duplicate: all-selector admission, shared authority, kernel failure, CLOEXEC and debugger fallback");
}
static long watch_mask_trace(int request,pid_t pid,void *address,void *data) {
    assert(request==PTRACE_GETSIGMASK && pid==123 && address && data);
    ++fd_resume_calls; errno=ESRCH; return -1;
}
static void native_ioctl_route(void) {
    const unsigned commands[]={FIONBIO,0x12345678,FIONREAD};
    for (unsigned i=0;i<sizeof(commands)/sizeof(*commands);++i) {
        struct thread t={.pid=123,.phase=IDLE,.ready=1,.application_filter=1};
        t.resources.files=md_fd_origins_new(); assert(t.resources.files);
        struct user_pt_regs regs={0}; regs.regs[8]=SYS_ioctl; regs.regs[0]=5;
        regs.regs[1]=commands[i]; regs.regs[2]=1;
        fd_expected_resume=PTRACE_CONT; fd_resume_calls=0;
        checkpoints.trace=i==2 ? watch_mask_trace : fd_resume_trace;
        thread_index[t.pid%THREAD_BUCKETS]=&t;
        proc_lookup_begin(&t,&regs);
        assert(fd_resume_calls==1 && !t.proc_lookup.table && t.phase==IDLE);
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
    t.original.regs[8]=call.nr;
    for (unsigned i=0;i<6;++i) t.original.regs[i]=call.args[i];
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
    struct thread a={.pid=123,.entry_fence_waiting=1,.entry_fence_status=17},
        b={.pid=124,.entry_fence_waiting=1,.entry_fence_status=19};
    a.next=&b; threads=&a; entry_fence_waiters=2;
    entry_fence_memory=md_memory_new(); assert(entry_fence_memory);
    a.memory=b.memory=entry_fence_memory;
    entry_fence_stage=2; entry_fence_error=-EBUSY;
    int status; struct rusage usage;
    assert(entry_fence_take(&status,&usage)==123 && status==17);
    assert(a.entry_fence_result==-EBUSY && !a.entry_fence_waiting && entry_fence_waiters==1);
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
    peer.direct=DIRECT_RESULT; assert(!entry_fence_adapt(&peer)); peer.direct=DIRECT_NONE;
    peer.entry_fence_waiting=1; assert(!entry_fence_adapt(&peer)); peer.entry_fence_waiting=0;
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
        peer.original.regs[8]=waits[i]; assert(entry_fence_drained(&peer));
        peer.machine.draining=1; assert(!entry_fence_drained(&peer)); peer.machine.draining=0;
    }
    peer.original.regs[8]=SYS_rt_sigtimedwait; assert(!entry_fence_drained(&peer));
    peer.original.regs[8]=SYS_process_vm_writev; assert(!entry_fence_drained(&peer));
    md_memory_release(entry_fence_memory); entry_fence_memory=NULL; entry_fence_stage=0;
    close(fd_ready); fd_ready=previous;
    puts("PASS supervisor entry fence: waiter retirement, transition ownership, private drain and admission hold");
}
static void control_deadlines(void) {
    struct md_control_pending late={.deadline=500},early={.deadline=100};
    struct thread a={.pid=123,.control_pending=&late},b={.pid=124,.control_pending=&early},idle={.pid=125};
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
static void pid_collection_waiters(void) {
    const enum phase phases[]={PROCESS_OBSERVING,PROCESS_INFO_LOAD,PROCESS_INFO_STORE,
        DEBUG_WAIT_ENTRY,DEBUG_WAIT_EXIT};
    const pid_t retired=INT_MAX;
    struct md_identity identity={0};
    struct thread first={.pid=123},last={.pid=124};
    first.next=&last; threads=&first;
    tasks=md_task_registry_create(); assert(tasks && pid_gone(retired));
    for (unsigned i=0;i<sizeof(phases)/sizeof(*phases);++i) {
        assert(!md_task_registry_publish(tasks,retired,retired,&identity,NULL));
        md_task_registry_retire(tasks,retired,0,&identity);
        first.phase=IDLE; last.pid=124; last.phase=phases[i];
        pid_collection_pending=1; pid_collect();
        assert(pid_collection_pending && pid_collection_blocker==&last);
        assert(md_task_registry_has_retired(tasks));
        /* An earlier waiter must not replace a still-valid hint on each event. */
        first.phase=PROCESS_INFO_LOAD;
        for (unsigned event=0;event<1000;++event) {
            pid_collect(); assert(pid_collection_blocker==&last);
        }
        last.phase=IDLE; pid_collect();
        assert(pid_collection_pending && pid_collection_blocker==&first);
        /* An exited slot is not a waiter, but a reused live slot can be one. */
        first.pid=0; last.pid=125; last.phase=phases[i]; pid_collect();
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
    assert(!md_buffer_span(&p->task->buffer,&span) && !memcmp(span.data,"data",4));
    ++p->commits; p->copied=copied;
}
static void terminal_buffer_completion(void) {
    for (int kind=-1;kind<=MD_PROC_FILE_CONTROL;++kind) {
        unsigned flags=MD_DISPATCH_PATHS|MD_DISPATCH_NETWORK_SCOPED|MD_DISPATCH_DEVICE_EVENTS;
        unsigned context=md_dispatch_control(flags,kind);
        assert((context&0xffff)==flags && md_dispatch_control_kind(context)==kind);
    }
    const struct md_control_file_ops ops={.retain=completion_retain,.release=completion_release,
        .access=completion_access,.read=completion_read,.write=completion_write,.read_commit=completion_commit};
    struct md_interception_buffers *pool=NULL;
    assert(!md_buffers_create(1,4096,0,0,&pool));
    for (unsigned terminal=0;terminal<2;++terminal) for (unsigned mode=0;mode<5;++mode) {
        struct thread t={.buffer_open.fd=-1,.proc_image_fd=-1,.control_mapping_fd=-1};
        struct completion_probe probe={.task=&t};
        assert(!md_buffers_take(pool,&t.buffer));
        struct md_proc_file *file=NULL;
        if (mode!=3) {
            file=md_proc_file_open_control(&ops,&probe,&(struct md_control_opener){.identity=&t.identity},O_RDONLY); assert(file);
            assert(md_proc_file_io_begin(&t.proc_io,file,MD_PROC_FILE_READ,0,0,0,4,NULL)==1);
            struct md_buffer_span span; assert(!md_buffer_span(&t.buffer,&span));
            assert(md_proc_file_io_execute(&t.proc_io,&t.identity,NULL,span.data,span.size,4096)==4);
            assert(!md_proc_file_io_offer(&t.proc_io));
        }
        struct outgoing_owner busy={.stage=OUTGOING_SEND};
        if (mode==4) t.outgoing=&busy;
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
        assert(!t.proc_io.file && !!t.buffer.pool==(mode==4));
        t.outgoing=NULL;
        assert(!buffer_cleanup(&t));
        md_proc_file_release(file);
        /* The sole slot is reusable immediately, including a failed finish. */
        assert(!md_buffers_take(pool,&t.buffer) && !md_buffer_release(&t.buffer));
    }
    for (unsigned seek=0;seek<2;++seek) {
        struct thread t={.buffer_open.fd=-1,.proc_image_fd=-1,.control_mapping_fd=-1};
        struct completion_probe probe={.task=&t};
        assert(!md_buffers_take(pool,&t.buffer));
        struct md_proc_file *file=md_proc_file_open_control(&ops,&probe,&(struct md_control_opener){.identity=&t.identity},O_RDWR); assert(file);
        assert(md_proc_file_io_begin(&t.proc_io,file,seek ? MD_PROC_FILE_SEEK : MD_PROC_FILE_WRITE,
            0,seek ? 7 : 0,SEEK_SET,seek ? 0 : 4,NULL)==1);
        struct md_buffer_span span; assert(!md_buffer_span(&t.buffer,&span));
        memcpy(span.data,"data",4);
        long expected=seek ? 7 : 4;
        assert(md_proc_file_io_execute(&t.proc_io,&t.identity,NULL,span.data,span.size,4096)==expected);
        assert(!md_proc_file_io_offer(&t.proc_io));
        assert(buffer_result_finish(&t,0,expected)==expected);
        assert(!probe.commits && !t.proc_io.file && !t.buffer.pool);
        md_proc_file_release(file);
    }
    for (unsigned mode=0;mode<3;++mode) {
        struct thread t={.buffer_open.fd=-1,.proc_image_fd=-1,.control_mapping_fd=-1};
        assert(!md_buffers_take(pool,&t.buffer));
        long result=mode==0 ? -EAGAIN : mode==1 ? 32 : MD_CALL_NATIVE;
        assert(buffer_result_finish(&t,0,result)==result && !t.buffer.pool);
        assert(!md_buffers_take(pool,&t.buffer) && !md_buffer_release(&t.buffer));
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
    assert(!p->copied && !p->disposed && !p->task->control_publication);
    assert(!md_buffer_span(&p->task->buffer,&span));
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
        struct thread t={.buffer_open.fd=-1,.proc_image_fd=-1,.control_mapping_fd=-1};
        struct ioctl_publication_probe p={.pending={.copied=ioctl_publication_copied,
            .dispose=ioctl_publication_dispose},.task=&t};
        assert(!md_buffers_take(pool,&t.buffer));
        struct md_buffer_span span; assert(!md_buffer_span(&t.buffer,&span));
        memcpy(span.data,"reply",6);
        t.control_pending=&p.pending;
        if (mode==5) p.pending.copied=NULL;
        control_pending_complete(&t,mode==4 ? -ENOENT : 0);
        assert(!t.control_pending && !!t.control_publication==(mode<4));
        assert(p.disposed==(mode>=4) && !p.copied);
        long result=mode==1 ? -EFAULT : 0;
        if (mode==2) assert(!buffer_cleanup(&t));
        else if (mode==3) {
            control_publication_finish(&t,-ECANCELED);
            assert(!buffer_cleanup(&t));
        } else assert(buffer_result_finish(&t,0,result)==result);
        assert(p.disposed==1 && p.copied==(mode<4) && !t.control_publication);
        if (mode<4) assert(p.result==(mode>=2 ? -ECANCELED : result));
        control_publication_finish(&t,0);
        assert(!buffer_cleanup(&t) && p.disposed==1);
        assert(!md_buffers_take(pool,&t.buffer) && !md_buffer_release(&t.buffer));
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
        struct thread t={.buffer_open.fd=-1,.proc_image_fd=-1,.control_mapping_fd=-1};
        assert(!md_buffers_take(pool,&t.buffer));
        struct md_buffer_span span; assert(!md_buffer_span(&t.buffer,&span));
        struct seccomp_notif *received=span.data;
        memset(received,0,sizeof(*received));
        assert(notify_ioctl(file,SECCOMP_IOCTL_NOTIF_RECV,received,sizeof(*received),&t.control_pending)==-EINPROGRESS);
        assert(t.control_pending && t.control_pending->deadline==INT64_MAX);
        struct seccomp_data call={.nr=SYS_getpid,.arch=AUDIT_ARCH_AARCH64,.instruction_pointer=0x12345004,
            .args={1,2,3,4,5,6}};
        struct md_seccomp_notification *request=NULL;
        assert(!md_seccomp_notify_begin(q,456,NULL,&call,&request));
        assert(poll(&watch,1,0)==1 && file.ops->poll(file.context)==(POLLIN|POLLRDNORM));
        /* Pending receive captured zero-initialization once. A later guest
         * write cannot retarget the queued operation. */
        memset(received,0x55,sizeof(*received));
        assert(t.control_pending->resume(t.control_pending,received,sizeof(*received))==0);
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
        assert(!t.control_pending && !t.control_publication);
        if (mode==4) {
            assert(notify_valid(file,id)==-ENOENT);
            assert(notify_send(file,expected)==-ENOENT);
            assert(file.ops->poll(file.context)==0);
            continue;
        }
        if (mode>=2 && mode!=5) {
            assert(file.ops->poll(file.context)==(POLLIN|POLLRDNORM));
            assert(notify_valid(file,id)==-ENOENT && notify_send(file,expected)==-EINPROGRESS);
            assert(!md_buffers_take(pool,&t.buffer) && !md_buffer_span(&t.buffer,&span));
            received=span.data; memset(received,0,sizeof(*received));
            assert(!notify_ioctl(file,SECCOMP_IOCTL_NOTIF_RECV,received,sizeof(*received),&t.control_pending));
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
    struct thread t={.pid=123,.phase=DISPATCHING,.memory=md_memory_new(),.buffer_waiting=1};
    assert(t.memory && !md_memory_buffers_prepare(t.memory,1,4096,0,0));
    t.buffer_registers.regs[0]=MD_BUFFER_PROC_START;
    t.buffer_registers.regs[1]=4;
    struct md_interception_buffer held={0};
    assert(!md_buffers_take(md_memory_buffers(t.memory),&held));
    buffer_take(&t);
    assert(t.buffer_waiting && !t.buffer.pool && !t.proc_io.file && !t.proc_io.turn.queue);
    assert(t.retries[RETRY_BUFFER>=RETRY_FD].mask==RETRY_BUFFER);
    /* Cancellation while waiting has neither provider work nor a buffer to
     * retire; freeing capacity cannot resurrect the removed continuation. */
    retry_clear(&t,RETRY_BUFFER); t.buffer_waiting=0;
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
        t.signal_control=calloc(1,sizeof(*t.signal_control)); assert(t.signal_control);
        t.signal_control->started=t.signal_control->reacquiring=returning;
        signal_control_progress(&t);
        assert(!t.signal_control->scratch.pool && !t.signal_control->memory.queue);
        struct md_resource_order other={0};
        assert(md_memory_order_begin(t.memory,&other,NULL)==1);
        assert(!md_resource_order_start(&other) && !md_resource_order_complete(&other));
        signal_control_discard(&t);
    }
    assert(!md_buffer_release(&held)); md_memory_release(t.memory);
    puts("PASS signal buffer pressure: initial and returning waits never hold memory admission while awaiting a pool slot");
}
static void prepared_input(void) {
    struct thread t={.memory=md_memory_new(),.buffer_open.fd=-1,
        .proc_image_fd=-1,.control_mapping_fd=-1};
    struct md_identity identity=md_identity_new(1000,1000);
    struct md_process_limits limits;
    assert(!md_process_limits_read(&limits));
    t.resources.process=md_process_member_new(&identity,&limits);
    assert(t.memory && t.resources.process);
    assert(!md_memory_buffers_prepare(t.memory,1,sizeof(union md_adapter_frame),0,0));
    struct md_interception_buffers *pool=md_memory_buffers(t.memory);
    t.original.regs[8]=SYS_recvmsg;
    assert(!dispatch_input(&t,0) && !t.buffer.pool && !t.dispatch_prepared);
    assert(!md_memory_buffers_mapped(t.memory,0x100000,md_buffers_size(pool)));
    t.original.regs[8]=SYS_execve;
    assert(!dispatch_input(&t,0) && !t.buffer.pool);
    strcpy(t.endpoint,"managed");
    unsigned calls[]={SYS_recvmsg,SYS_recvmmsg,SYS_ppoll,SYS_pselect6,SYS_execve,SYS_execveat};
    unsigned adapters[]={MD_DISPATCH_NETWORK_DATA,MD_DISPATCH_NETWORK_CONTROL,MD_DISPATCH_DEVICE_EVENTS};
    for (unsigned i=0;i<sizeof(calls)/sizeof(*calls);++i) {
        t.original.regs[8]=calls[i]; t.original.regs[0]=t.original.regs[1]=8;
        for (unsigned j=0;j<sizeof(adapters)/sizeof(*adapters);++j)
            assert(!dispatch_input(&t,MD_DISPATCH_PATHS|adapters[j]) && !t.buffer.pool && !t.dispatch_prepared);
        assert(dispatch_input(&t,0)==0x100000 && t.dispatch_prepared);
        struct md_interception_buffer held={0};
        assert(md_buffers_take(pool,&held)==-EAGAIN);
        struct md_buffer_span span;
        assert(!md_buffer_span(&t.buffer,&span));
        memset(span.data,0xa5,span.size);
        /* An entry-prepared receive keeps its exact input/result frame when
         * the guest publication continuation acquires its adapter stack. */
        assert(dispatch_input(&t,0)==0x100000 && t.dispatch_prepared);
        for (size_t byte=0;byte<span.size;++byte)
            assert(((unsigned char *)span.data)[byte]==0xa5);
        assert(md_buffers_take(pool,&held)==-EAGAIN);
        /* Early validation failure and ordinary FINISH share exact cleanup. */
        assert(!buffer_cleanup(&t) && !t.dispatch_prepared && !t.buffer.pool);
        assert(!md_buffers_take(pool,&held));
        assert(!dispatch_input(&t,0) && !t.dispatch_prepared && !t.buffer.pool);
        assert(!md_buffer_release(&held));
    }
    t.original.regs[8]=SYS_ppoll;
    t.original.regs[1]=md_process_native_nofile(t.resources.process)+1;
    assert(!dispatch_input(&t,0) && !t.buffer.pool);
    t.original.regs[8]=SYS_pselect6; t.original.regs[0]=ULONG_MAX;
    assert(!dispatch_input(&t,0) && !t.buffer.pool);
    t.original.regs[0]=INT_MAX;
    assert(!dispatch_input(&t,0) && !t.buffer.pool);
    t.original.regs[8]=SYS_write;
    assert(!dispatch_input(&t,0) && !t.buffer.pool);
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
    assert(t.retries[1].mask==RETRY_READINESS && !t.buffer.pool && owner->detached);
    struct md_buffer_span span;
    assert(!readiness_frame(&t,&span) && span.data==owner->detached);
    assert(!md_buffer_release(&held));
    retry_clear(&t,RETRY_READINESS);
    assert(readiness_restore(&t,READINESS_RESTORE_REPLY,1));
    assert(!owner->detached && !owner->restoring && t.buffer.pool);
    assert(!readiness_frame(&t,&span));
    struct md_epoll_frame *frame=span.data;
    assert(frame->events[0].data.u64==73 && frame->timeout.tv_sec==19);
    assert(!md_buffer_release(&t.buffer));
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
        struct thread t={.pid=getpid(),.tgid=getpid(),.ready=1,.mapped_image=1,
            .identity=md_identity_new(1000,1234),.exec_backings={-1,-1},.memory=md_memory_new()};
        assert(t.memory && !md_resources_init(&t.resources,NULL));
        t.pending_identity=(struct md_exec_identity){.value=t.identity,.secure=secure};
        if (secure) t.pending_identity.value.uid.effective=0;
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
        t.exec_backings[0]=retained;
        assert(!image_enter(&t,(uintptr_t)&image,(uintptr_t)aux,5,machine));
        assert(md_process_dumpable(t.resources.access)==!secure);
        assert(t.entered && !t.mapped_image && t.exec_backings[0]==-1);
        assert(t.image_backings_ready==(machine && mode<4));
        int backing=-1; uint64_t offset=1;
        assert(md_memory_file_view(t.memory,(uintptr_t)mapping,page,&backing,&offset)==t.image_backings_ready);
        if (t.image_backings_ready) assert(backing>=0 && !offset && fcntl(backing,F_GETFD)>=0);
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
    t.proc_file=md_proc_file_open_control(&ops,&t,&(struct md_control_opener){.identity=&t.identity},O_WRONLY); assert(t.proc_file);
    char bytes[MD_CONTROL_IO_MAX]; memset(bytes,'x',sizeof(bytes)); memcpy(bytes,"data",4);
    size_t total;
    for (unsigned vector=0;vector<2;++vector) {
        struct iovec iov[3]={{(void *)1,0},{bytes,2},{bytes+2,2}};
        t.original.regs[8]=vector ? SYS_writev : SYS_write;
        t.original.regs[1]=(uintptr_t)(vector ? (void *)iov : bytes);
        t.original.regs[2]=vector ? 3 : 4;
        assert(proc_write_capture(&t,&total) && total==4);
        struct md_buffer_span span; assert(!md_buffer_span(&t.buffer,&span));
        assert(!memcmp(span.data,"data",4) && !t.proc_io.file && !t.proc_io.turn.queue);
        assert(!md_buffer_release(&t.buffer));
        struct md_interception_buffer held={0};
        assert(!md_buffers_take(md_memory_buffers(t.memory),&held));
        assert(!proc_write_capture(&t,&total) && !t.buffer.pool && !t.proc_io.file);
        assert(!md_buffer_release(&held));
    }
    struct iovec iov[3]={{bytes,sizeof(bytes)},{(void *)1,10}};
    t.original.regs[1]=(uintptr_t)iov; t.original.regs[2]=2;
    /* Only the prefix accepted by control IO is copied; untouched trailing
     * vectors cannot cause a fault or change the amount offered to the OFD. */
    assert(proc_write_capture(&t,&total) && total==sizeof(bytes)+10);
    struct md_buffer_span span; assert(!md_buffer_span(&t.buffer,&span));
    assert(!memcmp(span.data,bytes,sizeof(bytes)) && !md_buffer_release(&t.buffer));
    for (unsigned mode=0;mode<6;++mode) {
        iov[0]=(struct iovec){bytes,2}; iov[1]=(struct iovec){bytes+2,2};
        t.original.regs[1]=(uintptr_t)iov; t.original.regs[2]=2;
        if (mode==0) iov[1].iov_base=(void *)1;
        if (mode==1) t.original.regs[1]=1;
        if (mode==2) t.original.regs[2]=MD_PROC_IO_VECTORS+1;
        if (mode==3) iov[0].iov_len=PTRDIFF_MAX;
        if (mode==4) iov[0].iov_len=iov[1].iov_len=0;
        if (mode==5) t.debug_call_active=1;
        assert(!proc_write_capture(&t,&total) && !t.buffer.pool && !t.proc_io.file);
        t.debug_call_active=0;
    }
    size_t page=(size_t)getpagesize();
    char *mapping=mmap(NULL,page*2,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(mapping!=MAP_FAILED && !mprotect(mapping+page,page,PROT_NONE));
    t.original.regs[8]=SYS_write; t.original.regs[1]=(uintptr_t)(mapping+page-2); t.original.regs[2]=4;
    assert(!proc_write_capture(&t,&total) && !t.buffer.pool && !t.proc_io.file);
    assert(!munmap(mapping,page*2));
    int ready[2]; assert(!pipe(ready));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(ready[0]); assert(!prctl(PR_SET_DUMPABLE,0,0,0,0));
        assert(write(ready[1],"x",1)==1); for (;;) pause();
    }
    close(ready[1]); char value; assert(read(ready[0],&value,1)==1); close(ready[0]);
    t.pid=child; t.original.regs[1]=(uintptr_t)bytes;
    assert(!proc_write_capture(&t,&total) && !t.buffer.pool && !t.proc_io.file);
    assert(!kill(child,SIGKILL)); int status; assert(waitpid(child,&status,0)==child && WIFSIGNALED(status));
    md_proc_file_release(t.proc_file); md_memory_release(t.memory);
    puts("PASS write capture: scalar/vector prefix, empty vector, overflow, partial fault, protected caller and pressure without IO effects");
}
static void repair_events(void) {
    int previous=fd_ready;
    fd_ready=eventfd(0,EFD_NONBLOCK|EFD_CLOEXEC); assert(fd_ready>=0);
    struct thread caller={.pid=123,.phase=OBSERVING,.memory_action=MEMORY_KERNEL};
    caller.original.regs[8]=SYS_mprotect;
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
        caller.original.regs[8]=SYS_mmap;
        assert(!quiescence_repair_event(caller.pid,status,&usage));
        caller.original.regs[8]=SYS_mprotect;
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
            struct thread actor={.pid=123,.phase=OBSERVING,.memory_action=MEMORY_KERNEL};
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
            assert(actor.phase==OBSERVING && actor.memory_action==MEMORY_KERNEL);
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
        struct thread actor={.pid=123,.phase=OBSERVING,.memory_action=MEMORY_KERNEL};
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
        assert(loan_retirements==&retirement && actor.phase==OBSERVING && actor.memory_action==MEMORY_KERNEL);
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
            struct thread caller={.pid=123,.phase=OBSERVING,.memory_action=MEMORY_KERNEL};
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
            assert(caller.phase==OBSERVING && caller.memory_action==MEMORY_KERNEL);
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
static void debugger_test_complete(pid_t pid,struct user_pt_regs *r,long value) {
    (void)pid; (void)r; debugger_result=value;
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
            t.vfork_child=mode==3 ? 456 : 0;
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
    const int continuations[]={PTRACE_CONT,PTRACE_SYSCALL,PTRACE_SINGLESTEP,PTRACE_SYSEMU};
    for (unsigned mode=0;mode<sizeof(continuations)/sizeof(*continuations);++mode) {
        struct thread t={.pid=123,.born=1,.initial_stop=1,.entered=1,.process_image=1};
        assert(!debugger && !thread_index[t.pid%THREAD_BUCKETS]);
        thread_index[t.pid%THREAD_BUCKETS]=&t;
        checkpoints.trace=debugger_quiesce_trace;
        debugger_interrupts=debugger_continuations=0;
        struct md_task_quiesce *barrier=md_task_quiesce_new(quiesce_trace); assert(barrier);
        assert(!md_task_quiesce_add(barrier,t.pid,0,continuations[mode]));
        assert(!md_task_quiesce_stop(barrier) && debugger_interrupts==1);
        assert(!quiesce_event(barrier,t.pid,(PTRACE_EVENT_STOP<<16)|(SIGTRAP<<8)|0x7f,NULL));
        assert(md_task_quiesce_ready(barrier) && !md_task_quiesce_release(barrier));
        assert(debugger_continuations==1 && !t.memory_resume && !t.interrupt_pending);
        assert(t.kernel_resume==continuations[mode]);
        md_task_quiesce_destroy(barrier);
        thread_index[t.pid%THREAD_BUCKETS]=NULL;
    }
    struct md_debugger_host host={.group=debugger_test_group,.parent=debugger_test_parent,
        .attachable=debugger_test_attachable,.observe=debugger_test_observe,
        .options=debugger_test_options,.interrupt=debugger_interrupt,.complete=debugger_test_complete,
        .uid=debugger_test_uid,.usage=debugger_test_usage};
    for (unsigned enabled=0;enabled<2;++enabled) {
        host.seccomp_events=enabled;
        debugger=md_debugger_create(&host); assert(debugger);
        struct user_pt_regs request={.regs={[8]=SYS_ptrace,[0]=PTRACE_SEIZE,
            [1]=123,[3]=PTRACE_O_TRACESECCOMP}};
        assert(md_debugger_call(debugger,getpid(),&request));
        assert(debugger_result==(enabled ? 0 : -EIO));
        if (enabled) {
            struct user_pt_regs original={.regs={[8]=SYS_mount,[0]=17,[1]=19},.pc=0x4004,.sp=0x8000};
            assert(md_debugger_seccomp(debugger,123,&original,0x321));
            struct ptrace_syscall_info info={0};
            request.regs[0]=PTRACE_GET_SYSCALL_INFO; request.regs[2]=sizeof(info);
            request.regs[3]=(uintptr_t)&info;
            assert(md_debugger_call(debugger,getpid(),&request));
            assert(debugger_result==(long)(offsetof(struct ptrace_syscall_info,seccomp.ret_data)+sizeof(info.seccomp.ret_data)));
            assert(info.op==PTRACE_SYSCALL_INFO_SECCOMP && info.seccomp.nr==SYS_mount
                && info.seccomp.ret_data==0x321 && info.seccomp.args[0]==17
                && info.instruction_pointer==original.pc && info.stack_pointer==original.sp);
            unsigned char short_info[8]; memset(short_info,0x5a,sizeof(short_info));
            request.regs[2]=1; request.regs[3]=(uintptr_t)short_info;
            assert(md_debugger_call(debugger,getpid(),&request));
            assert(short_info[0]==PTRACE_SYSCALL_INFO_SECCOMP && short_info[1]==0x5a);
            request.regs[0]=PTRACE_SETOPTIONS; request.regs[2]=request.regs[3]=0;
            assert(md_debugger_call(debugger,getpid(),&request) && !debugger_result);
            assert(!md_debugger_seccomp(debugger,123,&original,0x322));
        }
        md_debugger_destroy(debugger); debugger=NULL;
    }
    host.seccomp_events=0;
    for (unsigned mode=0;mode<sizeof(continuations)/sizeof(*continuations);++mode) for (unsigned seized=0;seized<2;++seized) {
        struct thread t={.pid=123,.born=1,.initial_stop=1,.entered=1,.process_image=1};
        assert(!thread_index[t.pid%THREAD_BUCKETS]); thread_index[t.pid%THREAD_BUCKETS]=&t;
        checkpoints.trace=debugger_quiesce_trace;
        debugger_interrupts=debugger_continuations=0;
        debugger=md_debugger_create(&host); assert(debugger);
        struct md_task_quiesce *barrier=md_task_quiesce_new(quiesce_trace); assert(barrier);
        assert(!md_task_quiesce_add(barrier,t.pid,0,continuations[mode]));
        assert(!md_task_quiesce_stop(barrier) && debugger_interrupts==1);
        struct user_pt_regs call={.regs={[8]=SYS_ptrace,[0]=seized ? PTRACE_SEIZE : PTRACE_ATTACH,[1]=t.pid}};
        assert(md_debugger_call(debugger,getpid(),&call) && !debugger_result);
        if (seized) {
            call.regs[0]=PTRACE_INTERRUPT;
            assert(md_debugger_call(debugger,getpid(),&call) && !debugger_result);
        }
        assert(debugger_interrupts==2 && t.interrupt_pending);
        /* Both owners receive the same kernel interruption. Barrier release
         * must return to dispatcher admission before any guest continuation. */
        assert(!quiesce_event(barrier,t.pid,(PTRACE_EVENT_STOP<<16)|(SIGTRAP<<8)|0x7f,NULL));
        assert(md_task_quiesce_ready(barrier) && !md_task_quiesce_release(barrier));
        assert(!debugger_continuations && t.memory_resume && t.kernel_stopped && !t.interrupt_pending);
        assert(md_debugger_boundary(debugger,t.pid));
        assert(md_debugger_stopped_by(debugger,t.pid,getpid()));
        md_task_quiesce_destroy(barrier);
        md_debugger_destroy(debugger); debugger=NULL;
        thread_index[t.pid%THREAD_BUCKETS]=NULL;
    }
    checkpoints.trace=checkpoint_native_trace;
    puts("PASS debugger quiescence: attach/seize share barrier interrupt, all continuations retain pending stop");
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
        assert(!t.broker_event.held && !t.buffer.pool && !t.buffer_call);
        assert(!md_signal_delivery_pending(&t.signal_delivery) && !t.retained_signal);
        struct user_pt_regs result;
        io=(struct iovec){&result,sizeof(result)};
        assert(!ptrace(PTRACE_GETREGSET,pid,(void *)NT_PRSTATUS,&io));
        assert((long)result.regs[0]==(!mode ? -EAGAIN : adoption_error));
        assert(!ptrace(PTRACE_CONT,pid,NULL,(void *)(intptr_t)signal));
    } else {
        assert(t.broker_event.held && t.buffer.pool && t.buffer_call);
        assert(md_signal_delivery_pending(&t.signal_delivery));
        /* Simulate worker completion at an internal checkpoint. The old
         * delivery stop is suppressed; only the queue publishes the signal. */
        assert(!md_signal_delivery_publish(&t.signal_delivery,pid,pid));
        assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
        assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status) && WSTOPSIG(status)==signal);
        int deliver=signal;
        assert(md_signal_delivery_event(&t.signal_delivery,pid,signal,&deliver)>=0);
        assert(deliver==signal && !md_signal_delivery_pending(&t.signal_delivery));
        assert(!ptrace(PTRACE_CONT,pid,NULL,(void *)(intptr_t)deliver));
        md_fs_buffer_open_release(&adopted); t.buffer_call=NULL;
    }
    assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!md_buffer_release(&held) && !md_buffer_release(&t.buffer));
    md_signal_delivery_clear(&t.signal_delivery); md_memory_release(t.memory);
    thread_index[(unsigned)pid%THREAD_BUCKETS]=NULL;
}
extern void test_wait_svc(void);
__asm__(".text\n.balign 4\ntest_wait_svc:\nsvc #0\nret\n");
static void provider_wait_restarts(void) {
    struct thread t={.original={.regs={[8]=SYS_read}}};
    assert(control_wait_restart(&t)==RESTART_SIGNAL);
    t.proc_io.executed=1;
    assert(!control_wait_restart(&t));
    struct md_control_pending pending={.retry_preparation=1};
    for (unsigned io=0;io<2;++io) {
        t.control_pending=io ? NULL : &pending;
        t.proc_io.pending=io ? &pending : NULL;
        assert(control_wait_restart(&t)==RESTART_PREPARATION);
        pending.retry_preparation=0;
        assert(!control_wait_restart(&t));
        pending.retry_preparation=1;
        t.proc_io.transferred=1;
        assert(!control_wait_restart(&t));
        t.proc_io.transferred=0;
        t.original.regs[8]=SYS_splice;
        assert(!control_wait_restart(&t));
        t.original.regs[8]=SYS_read;
        assert(control_wait_restart(&t)==RESTART_PREPARATION);
    }
    puts("PASS provider wait restart: direct/IO preparation, committed effects, partial data and splice");
}
static void provider_wait_boundaries(void) {
    const enum phase phases[]={FILE_WAIT_ENTRY,CONTROL_WAIT_ENTRY,CLOSE_WAIT_ENTRY};
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
        struct thread t={.pid=pid,.phase=phases[p],.host_signals=md_app_uid_active};
        assert(!thread_index[(unsigned)pid%THREAD_BUCKETS]);
        thread_index[(unsigned)pid%THREAD_BUCKETS]=&t;
        assert(registers(pid,&t.original) && skip(pid));
        t.buffer_registers=t.close_registers=t.original;
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
        wait_cancel_entry(&t,FILE_WAITING);
        assert(t.phase==WAIT_CANCEL_EXIT && t.wait_resume==FILE_WAITING);
        assert(waitpid(pid,&status,0)==pid && WSTOPSIG(status)==(SIGTRAP|0x80));
        t.original.regs[0]=pid;
        assert(set_registers(pid,&t.original));
        assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
        assert(waitpid(pid,&status,0)==pid);
        if (!WIFEXITED(status) || WEXITSTATUS(status))
            fprintf(stderr,"provider wait phase=%d pending_exit=%u status=%#x\n",phases[p],pending_exit,status);
        assert(WIFEXITED(status) && !WEXITSTATUS(status));
        thread_index[(unsigned)pid%THREAD_BUCKETS]=NULL;
    }
    abi.task_call=saved_call; abi.task_return=saved_return;
    puts("PASS provider wait: seccomp exit drain, post-exit entry, poll arguments and cancelled completion");
}
static unsigned checkpoint_trace_calls,checkpoint_op;
static long checkpoint_barrier_trace(int request,pid_t pid,void *address,void *data) {
    assert(pid==123); ++checkpoint_trace_calls;
    if (request==PTRACE_GET_SYSCALL_INFO) {
        struct ptrace_syscall_info *info=data;
        *info=(struct ptrace_syscall_info){.op=checkpoint_op,.instruction_pointer=0x1204};
    } else if (request==PTRACE_GETREGSET) {
        assert((uintptr_t)address==NT_PRSTATUS);
        struct iovec *io=data;
        *(struct user_pt_regs *)io->iov_base=md_task_registers_entry(0x1204,0x8000,0);
    } else assert(request==PTRACE_SETREGSET || request==PTRACE_SYSCALL || request==PTRACE_SYSEMU);
    return 0;
}
static void checkpoint_quiescence(void) {
    struct thread t={.pid=123,.application_filter=1};
    thread_index[t.pid%THREAD_BUCKETS]=&t;
    struct md_task_checkpoints saved=checkpoints;
    checkpoints=(struct md_task_checkpoints){.trace=checkpoint_barrier_trace,
        .restricted=checkpoint_restricted,.marker=checkpoint_marker,.begin=0x1000,.end=0x2000};
    struct memory_execution memory={0}; struct mapping_owner mapping={0}; struct task_action action={0};
    struct retained_memory robust={0}; struct retained_retirement retirement={0};
    for (unsigned mode=1;mode<7;++mode) for (unsigned stage=0;stage<3;++stage)
            for (unsigned borrowed=0;borrowed<2;++borrowed) {
        struct user_pt_regs r=md_task_registers_entry(0x1200,0x8000,0);
        assert(set_registers(t.pid,&r) && !native_trace(PTRACE_CONT,t.pid,NULL,NULL));
        int status=(SIGTRAP|0x80)<<8|0x7f;
        for (unsigned i=0;i<stage;++i) {
            checkpoint_op=i==1 ? PTRACE_SYSCALL_INFO_EXIT : PTRACE_SYSCALL_INFO_ENTRY;
            assert(checkpoint_event(t.pid,&status)==1);
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
        unsigned before=checkpoint_trace_calls;
        if (borrowed) {
            struct md_task_quiesce_stop boundary;
            assert(!md_task_quiesce_borrow(*barrier,t.pid,&boundary));
            assert(checkpoint_event(t.pid,&status)==1 && checkpoint_trace_calls>before);
            assert(!md_task_quiesce_return(*barrier,t.pid));
        } else {
            assert(!checkpoint_event(t.pid,&status) && checkpoint_trace_calls==before);
            assert(!md_task_quiesce_event(*barrier,t.pid,status,NULL));
        }
        assert(md_task_quiesce_ready(*barrier) && !md_task_quiesce_release(*barrier));
        entry_fence_stage=0; memory_execution=NULL; mapping_execution=NULL; task_action_execution=NULL;
        policy_installer=NULL; retained_creation=NULL; retained_retirement=NULL;
        if (!borrowed) {
            assert(md_task_quiesce_take(*barrier,&status,NULL)==t.pid);
            assert(checkpoint_event(t.pid,&status)==1 && checkpoint_trace_calls>before);
        }
        assert(!md_task_quiesce_take(*barrier,&status,NULL));
        md_task_quiesce_destroy(*barrier); *barrier=NULL;
        md_task_checkpoints_clear(&checkpoints);
    }
    checkpoints=saved; thread_index[t.pid%THREAD_BUCKETS]=NULL;
    puts("PASS checkpoint quiescence: all barriers retain and replay once; borrowed helpers progress");
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
        assert(t.phase==DISPATCHING && t.broker_event.held && t.buffer_call && adopted.fd>=0);
        char byte=1; assert(read(adopted.fd,&byte,1)==1 && !byte);
        md_fs_buffer_open_release(&adopted); t.buffer_call=NULL;
        /* FD installation has its own result-custody suite. End this fixture
         * at the proven single adoption without executing its fake adapter. */
        regs=t.original; regs.regs[0]=-EINTR; assert(set_registers(pid,&regs));
    } else assert(t.phase==IDLE && (long)regs.regs[0]==-EOPNOTSUPP);
    assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
    assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!md_buffer_release(&t.buffer)); md_memory_release(t.memory);
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
    struct md_svc_registry *r=md_svc_registry_new((uintptr_t)frames,(uintptr_t)notification_bind_dispatch);
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
            assert(interrupted_operation(&t) && t.phase==IDLE && !t.signal_shielded);
        } else assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
        bind=admission_request();
        if (order==4 || order==5) completed_notification(pid,&result);
    }
    handle_notification(&bind);
    assert(!!t.broker_completion==(order<2 || order>=4) && !t.cooperative_buffer.pool && !adoption_calls);
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
        assert(t.phase==DISPATCHING && t.buffer_call && adopted.fd>=0);
        char byte=1; assert(read(adopted.fd,&byte,1)==1 && !byte);
        md_fs_buffer_open_release(&adopted); t.buffer_call=NULL;
        returned=t.original; returned.regs[0]=-EINTR; assert(set_registers(pid,&returned));
    }
    assert(returned.sp==original.sp && returned.pc==first.data.instruction_pointer);
    assert(!memcmp(returned.regs+1,original.regs+1,30*sizeof(original.regs[0])));
    assert(returned.pstate==original.pstate);
    assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
    assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!md_buffer_release(&t.buffer)); md_memory_release(t.memory);
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
        if (early) { handle_notification(&q); assert(t.memory_notification); }
        assert(!md_task_quiesce_stop(mapping_barrier));
        if (!early) handle_notification(&q);
        assert(!t.memory_notification && !t.buffer_admission_pending);
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
        t.buffer_admission_id=q.id; t.buffer_admission_pending=1;
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
            assert(!domain_call(&t,&regs,MD_INTERCEPT_FD));
        }
    }
    puts("PASS descriptor command selection: shared app/shell filter, unsigned ioctl command and FD owner");
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
    for (unsigned fault=0;fault<3;++fault) {
        uint64_t *output=mmap(NULL,getpagesize(),PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
        assert(output!=MAP_FAILED); output[1]=0x12345678;
        if (fault==1) assert(!mprotect(output,getpagesize(),PROT_READ));
        struct user_pt_regs regs={.regs={[8]=SYS_rt_sigprocmask,[0]=999,[2]=(uintptr_t)output,
            [3]=fault==2 ? 7 : 8}};
        struct md_task_signal_control query;
        unsigned char scratch[sizeof(struct md_task_copy_scratch)+sizeof(uint64_t)];
        uint64_t mask=MD_SIGNAL_SYS_BIT|(UINT64_C(1)<<(SIGUSR2-1));
        assert(md_task_signal_query_begin(&query,no_trace,getpid(),&regs,0x1000,0x2000,
            mask,scratch,(uintptr_t)scratch,sizeof(scratch))==1);
        assert(query.result==(fault==1 ? -EFAULT : fault==2 ? -EINVAL : 0));
        assert(output[0]==(fault ? 0 : mask) && output[1]==0x12345678);
        assert(!munmap(output,getpagesize()));
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
    struct signal_control *owner=calloc(1,sizeof(*owner)); assert(owner); t.signal_control=owner;
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
    signal_control_discard(&t); assert(!t.signal_control && !loan_retirements && !task_action_calls);
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
int main(void) {
    retained_continuation_custody();
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
    readiness_transport_pressure();
    signal_shield_ownership();
    metadata_replay_completion();
    metadata_policy_delivery();
    readiness_publication_eviction();
    readiness_terminal_replacement();
    process_snapshot();
    memory_context_capture();
    memory_observation_routes();
    entry_revocation_lost_stop();
    machine_context_admission();
    processor_controls();
    svc_client_lifetime();
    receive_layouts();
    native_capture();
    duplicate_retirement_order();
    fd_native_retirement();
    duplicate_prepared_admission();
    native_ioctl_route();
    queued_actions();
    barrier_admission(); readiness_admission_order(); readiness_entry_interrupt(); readiness_signal_return(); readiness_parked_signal_return(); readiness_cancel_entry(); readiness_admission_identity(); entry_fence_ownership(); control_deadlines(); pid_collection_waiters(); terminal_buffer_completion(); ioctl_publication_completion(); notification_queue(); virtual_listener_lifetime(); notification_policy_binding(); md_test_seccomp_notify(); buffered_io_pressure(); prepared_input(); captured_writes(); repair_events(); repair_retirement(); quiescent_birth(); event_accounting();
    for (unsigned mode=0;mode<4;++mode) {
        completed_open(mode,SIGUSR1); completed_open(mode,SIGRTMAX);
    }
    provider_wait_restarts(); provider_wait_boundaries();
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
    checkpoint_quiescence();
    quiescent_task_ownership();
    debugger_quiescence();
    md_task_checkpoints_clear(&checkpoints);
    puts("PASS supervisor completed open: exhausted buffer, failed adoption and single standard/realtime delivery");
    return 0;
}
