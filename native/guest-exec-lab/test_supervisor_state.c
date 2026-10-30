/* Compile the actual coordinator; only external admission/accounting boundaries
 * are fault-injected. No duplicate model of its state machine or product hooks. */
#define main supervisor_program_main
#include "../shroot/src/supervisor.c"
#undef main
#include <assert.h>
#include "fs_mount_internal.h"

static void process_snapshot(void) {
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
    struct mapping_owner mapping={0},other={0};
    struct task_action action={0},loan={0};
    struct memory_execution memory={0};
    assert(!quiescence_busy(NULL));
    entry_fence_stage=1;
    assert(quiescence_busy(NULL) && quiescence_busy(&mapping));
    /* Even a retirement without a requester must not acquire a table or actor. */
    loan_start(&loan);
    assert(!loan.files.table && !loan.actor && !task_action_execution && !cancelling);
    entry_fence_stage=2;
    assert(!quiescence_busy(NULL));
    entry_fence_stage=0;
    memory_execution=&memory;
    assert(quiescence_busy(NULL) && quiescence_busy(&mapping));
    memory_execution=NULL;
    task_action_execution=&action;
    assert(quiescence_busy(NULL) && quiescence_busy(&mapping));
    task_action_execution=NULL; task_action_releasing=1;
    assert(quiescence_busy(NULL) && quiescence_busy(&mapping));
    task_action_releasing=0; mapping_execution=&mapping;
    assert(quiescence_busy(NULL) && quiescence_busy(&other) && !quiescence_busy(&mapping));
    entry_fence_stage=1;
    assert(quiescence_busy(&mapping));
    entry_fence_stage=0; mapping_execution=NULL;
    puts("PASS supervisor admission: entry, repair, injection, release and exact nested retirement");
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
    struct readiness_owner prior={.loan_number=-1},activation={0};
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
static int fd_expected_resume, fd_resume_calls, fd_test_operation, fd_test_mode;
static long fd_resume_trace(int request,pid_t pid,void *address,void *data) {
    if (request!=fd_expected_resume || pid!=123 || address || data)
        fprintf(stderr,"FD resume op=%d mode=%d expected=%d actual=%d pid=%d address=%p data=%p\n",
            fd_test_operation,fd_test_mode,fd_expected_resume,request,pid,address,data);
    assert(request==fd_expected_resume && pid==123 && !address && !data);
    ++fd_resume_calls; return 0;
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
static void readiness_admission_identity(void) {
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    struct readiness_owner owner={.loan_number=-1};
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
    struct task_action retirement={0}; owner.retirement=&retirement;
    assert(readiness_admission_purpose(&t,&call)==MD_FS_BUFFER_EPOLL_LOAN);
    owner.loan_number=9; assert(!readiness_admission_purpose(&t,&call));
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
    entry_fence_barrier=md_task_quiesce_new(no_trace); assert(entry_fence_barrier);
    entry_fence_stage=2; entry_fence_error=-EBUSY;
    int status; struct rusage usage;
    assert(entry_fence_take(&status,&usage)==123 && status==17);
    assert(a.entry_fence_result==-EBUSY && !a.entry_fence_waiting && entry_fence_waiters==1);
    assert(entry_fence_owner==&a && !entry_fence_park(&a));
    assert(!entry_fence_take(&status,&usage));
    entry_fence_owner=NULL;
    /* Exit/exec cancels a waiter exactly once, before its slot can be reused. */
    entry_fence_cancel(&b); entry_fence_cancel(&b);
    assert(!entry_fence_waiters && !entry_fence_take(&status,&usage));
    assert(!entry_fence_memory && !entry_fence_barrier && !entry_fence_stage);
    threads=NULL;
    entry_fence_progress();
    assert(!entry_fence_memory);
    struct thread peer={.phase=DISPATCHING,.initial_stop=1};
    entry_fence_memory=md_memory_new(); assert(entry_fence_memory);
    peer.memory=entry_fence_memory; entry_fence_stage=2; entry_fence_protected=1;
    assert(!entry_fence_boundary(&peer) && !entry_fence_park(&peer));
    peer.phase=IDLE;
    assert(entry_fence_boundary(&peer) && entry_fence_park(&peer));
    peer.machine.draining=1; assert(!entry_fence_park(&peer)); peer.machine.draining=0;
    peer.direct=DIRECT_RESULT; assert(!entry_fence_park(&peer)); peer.direct=DIRECT_NONE;
    peer.entry_fence_waiting=1; assert(!entry_fence_park(&peer)); peer.entry_fence_waiting=0;
    entry_fence_protected=0;
    assert(!entry_fence_park(&peer) && entry_fence_adapt(&peer));
    entry_fence_stage=3; assert(!entry_fence_park(&peer));
    assert(!entry_fence_adapt(&peer));
    struct readiness_owner wait={.native_caller=1};
    peer.readiness=&wait; peer.phase=EPOLL_WAIT; entry_fence_stage=2;
    assert(entry_fence_drained(&peer) && !entry_fence_boundary(&peer) && !entry_fence_adapt(&peer));
    entry_fence_protected=1; assert(!entry_fence_drained(&peer)); entry_fence_protected=0;
    wait.evicting=1; assert(!entry_fence_drained(&peer)); wait.evicting=0;
    wait.finishing=1; assert(!entry_fence_drained(&peer)); wait.finishing=0;
    peer.machine.draining=1; assert(!entry_fence_drained(&peer)); peer.machine.draining=0;
    peer.phase=EPOLL_CALL; assert(!entry_fence_drained(&peer));
    peer.phase=SOCKET_WAITING; assert(!entry_fence_drained(&peer));
    peer.readiness=NULL; peer.phase=WATCH_READ; peer.watch_wait=2;
    assert(entry_fence_drained(&peer) && !entry_fence_boundary(&peer));
    entry_fence_protected=1; assert(!entry_fence_drained(&peer)); entry_fence_protected=0;
    peer.machine.syscall_exit=1; assert(!entry_fence_drained(&peer)); peer.machine.syscall_exit=0;
    peer.watch_wait=1; assert(!entry_fence_drained(&peer));
    peer.watch_wait=0; assert(entry_fence_drained(&peer));
    peer.watch_wait=2; peer.phase=WATCH_ENTRY; assert(!entry_fence_drained(&peer));
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
        md_task_registry_retire(tasks,retired,&identity);
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
    md_task_registry_retire(tasks,retired,&identity);
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
static void image_entry_identity(void) {
    tasks=md_task_registry_create(); assert(tasks);
    for (unsigned mode=0;mode<6;++mode) {
        unsigned secure=mode&1, machine=mode>=2;
        struct thread t={.pid=getpid(),.tgid=getpid(),.ready=1,.mapped_image=1,
            .identity=md_identity_new(1000,1234),.exec_backings={-1,-1},.memory=md_memory_new()};
        assert(t.memory);
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
        assert(image_enter(&t,(uintptr_t)&image,(uintptr_t)aux,5,machine)==(int)secure);
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
        md_identity_release(&t.identity); md_memory_release(t.memory);
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
    struct thread caller={.pid=123,.phase=OBSERVING,.memory_action=MEMORY_KERNEL};
    caller.original.regs[8]=SYS_mprotect;
    thread_index[caller.pid%THREAD_BUCKETS]=&caller;
    struct thread actor={.pid=456};
    struct mapping_owner mapping={.caller=actor.pid};
    struct task_action action={.actor=&actor};
    struct memory_execution memory={.actor=&actor};
    int status=(SIGTRAP|0x80)<<8|0x7f;
    struct rusage usage={.ru_stime.tv_usec=789};
    assert(!quiescence_repair_event(caller.pid,status,&usage));
    for (unsigned mode=0;mode<5;++mode) {
        struct md_task_quiesce **barrier;
        if (!mode) { entry_fence_stage=1; barrier=&entry_fence_barrier; }
        else if (mode==1) { memory_execution=&memory; barrier=&memory_barrier; }
        else if (mode==2) { task_action_execution=&action; barrier=&task_action_barrier; }
        else {
            mapping_execution=&mapping; barrier=&mapping_barrier;
            if (mode==4) task_action_execution=&action;
        }
        *barrier=md_task_quiesce_new(no_trace); assert(*barrier);
        caller.original.regs[8]=SYS_mmap;
        assert(!quiescence_repair_event(caller.pid,status,&usage));
        caller.original.regs[8]=SYS_mprotect;
        assert(!quiescence_repair_event(caller.pid,(SIGUSR1<<8)|0x7f,&usage));
        assert(!quiescence_repair_event(caller.pid,(PTRACE_EVENT_EXIT<<16)|(SIGTRAP<<8)|0x7f,&usage));
        memory.actor=&caller; action.actor=&caller; mapping.caller=caller.pid;
        if (mode) assert(!quiescence_repair_event(caller.pid,status,&usage));
        memory.actor=&actor; action.actor=&actor; mapping.caller=actor.pid;
        assert(quiescence_repair_event(caller.pid,status,&usage));
        assert(md_task_quiesce_contains(*barrier,caller.pid));
        assert(!md_task_quiesce_contains(*barrier,actor.pid));
        assert(md_task_quiesce_ready(*barrier));
        assert(!md_task_quiesce_release(*barrier));
        int replay=0; struct rusage replay_usage={0};
        assert(md_task_quiesce_take(*barrier,&replay,&replay_usage)==caller.pid);
        assert(replay==status && replay_usage.ru_stime.tv_usec==789);
        assert(!md_task_quiesce_take(*barrier,&replay,&replay_usage));
        md_task_quiesce_destroy(*barrier); *barrier=NULL;
        entry_fence_stage=0; memory_execution=NULL; task_action_execution=NULL; mapping_execution=NULL;
    }
    thread_index[caller.pid%THREAD_BUCKETS]=NULL;
    puts("PASS supervisor repair deferral: every owner, nested mapping, exact replay and unrelated progress");
}
static void unused_retirement(void *context,int error) { (void)context; (void)error; assert(0); }
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
    puts("PASS memory repair yields unmodified mapping to exact FD retirement, retains exit/accounting and never replays mprotect");
}
static long birth_trace(int request,pid_t pid,void *address,void *data) {
    assert(request==PTRACE_GETEVENTMSG && pid==123 && !address);
    *(unsigned long *)data=456;
    return 0;
}
static void quiescent_birth(void) {
    unsigned events[]={PTRACE_EVENT_CLONE,PTRACE_EVENT_FORK,PTRACE_EVENT_VFORK};
    struct thread child={.pid=456,.kernel_stopped=1};
    unsigned bucket=child.pid%THREAD_BUCKETS;
    assert(!thread_index[bucket]); thread_index[bucket]=&child;
    long (*saved_trace)(int,pid_t,void *,void *)=checkpoints.trace;
    checkpoints.trace=birth_trace;
    for (unsigned i=0;i<sizeof(events)/sizeof(*events);++i) {
        for (int early=0;early<2;++early) {
            child.initial_stop=early;
            struct md_task_quiesce *q=md_task_quiesce_new(birth_trace); assert(q);
            int status=(events[i]<<16)|(SIGTRAP<<8)|0x7f;
            struct rusage usage={.ru_maxrss=17},replayed;
            assert(!md_task_quiesce_add(q,123,1,0));
            assert(!quiesce_event(q,123,status,&usage));
            assert(md_task_quiesce_ready(q)==early);
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
        assert(request==PTRACE_CONT || request==PTRACE_SYSCALL || request==PTRACE_SINGLESTEP);
        ++debugger_continuations;
    }
    return 0;
}
static void debugger_quiescence(void) {
    const int continuations[]={PTRACE_CONT,PTRACE_SYSCALL,PTRACE_SINGLESTEP};
    for (unsigned mode=0;mode<3;++mode) {
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
        md_task_quiesce_destroy(barrier);
        thread_index[t.pid%THREAD_BUCKETS]=NULL;
    }
    struct md_debugger_host host={.group=debugger_test_group,.parent=debugger_test_parent,
        .attachable=debugger_test_attachable,.observe=debugger_test_observe,
        .options=debugger_test_options,.interrupt=debugger_interrupt,.complete=debugger_test_complete,
        .uid=debugger_test_uid,.usage=debugger_test_usage};
    for (unsigned mode=0;mode<3;++mode) for (unsigned seized=0;seized<2;++seized) {
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
static void provider_wait_boundaries(void) {
    const enum phase phases[]={FILE_WAIT_ENTRY,CONTROL_WAIT_ENTRY,CLOSE_WAIT_ENTRY};
    uintptr_t saved_gate=abi.raw_gate;
    abi.raw_gate=(uintptr_t)test_wait_svc+4;
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
        struct thread t={.pid=pid,.phase=phases[p]};
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
        assert(skip(pid));
        assert(!ptrace(PTRACE_SYSCALL,pid,NULL,NULL));
        assert(waitpid(pid,&status,0)==pid && WSTOPSIG(status)==(SIGTRAP|0x80));
        t.original.regs[0]=pid;
        assert(set_registers(pid,&t.original));
        assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
        assert(waitpid(pid,&status,0)==pid);
        if (!WIFEXITED(status) || WEXITSTATUS(status))
            fprintf(stderr,"provider wait phase=%d pending_exit=%u status=%#x\n",phases[p],pending_exit,status);
        assert(WIFEXITED(status) && !WEXITSTATUS(status));
    }
    abi.raw_gate=saved_gate;
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
    for (unsigned mode=0;mode<4;++mode) for (unsigned stage=0;stage<3;++stage)
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
        if (!mode) { entry_fence_stage=1; barrier=&entry_fence_barrier; }
        else if (mode==1) { memory_execution=&memory; barrier=&memory_barrier; }
        else if (mode==2) { mapping_execution=&mapping; barrier=&mapping_barrier; }
        else { task_action_execution=&action; barrier=&task_action_barrier; }
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
int main(void) {
    alarm(30);
    lost_stop_delivery();
    readiness_transport_pressure();
    readiness_terminal_replacement();
    process_snapshot();
    memory_context_capture();
    memory_observation_routes();
    processor_controls();
    svc_client_lifetime();
    receive_layouts();
    native_capture();
    duplicate_retirement_order();
    fd_native_retirement();
    barrier_admission(); readiness_admission_order(); readiness_entry_interrupt(); readiness_signal_return(); readiness_parked_signal_return(); readiness_cancel_entry(); readiness_admission_identity(); entry_fence_ownership(); control_deadlines(); pid_collection_waiters(); terminal_buffer_completion(); buffered_io_pressure(); prepared_input(); captured_writes(); repair_events(); repair_retirement(); quiescent_birth(); event_accounting();
    for (unsigned mode=0;mode<4;++mode) {
        completed_open(mode,SIGUSR1); completed_open(mode,SIGRTMAX);
    }
    provider_wait_boundaries();
    image_entry_identity();
    admission_quiescence();
    checkpoint_quiescence();
    debugger_quiescence();
    md_task_checkpoints_clear(&checkpoints);
    puts("PASS supervisor completed open: exhausted buffer, failed adoption and single standard/realtime delivery");
    return 0;
}
