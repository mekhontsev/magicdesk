#define _GNU_SOURCE
#include "svc_machine.h"
#include <assert.h>
#include <elf.h>
#include <errno.h>
#include <linux/filter.h>
#include <linux/futex.h>
#include <linux/ptrace.h>
#include <linux/seccomp.h>
#include <setjmp.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <ucontext.h>
#include <unistd.h>

extern long alias_call(long,long,long,long,long);
extern char alias_svc[],original_svc[];
__asm__(".text\n.balign 4\n.global alias_call\nalias_call:\n"
    "mov x8,x0\nmov x0,x1\nmov x1,x2\nmov x2,x3\nmov x3,x4\n"
    ".global alias_svc\nalias_svc:\nsvc #0\nb original_svc+4\n"
    ".global original_svc\noriginal_svc:\nsvc #0\nret\n");
enum { RESTART=1, ALTSTACK=2, FILTER=4, IGNORE=8, SUPPRESS=16, ESCAPE=32,
    NATIVE_ENTRY=64, QUIET_RETURN=128, KERNEL_CHILD=256 };
static struct { volatile int handled; } *shared;
static unsigned mode;
static int expected_restart, word;
static char alternate[65536];
static sigjmp_buf escape;
static unsigned info_reads,register_reads,register_writes;
static long signal_trace(int request,pid_t pid,void *address,void *data) {
    if (request==PTRACE_GETSIGINFO) ++info_reads;
    if (request==PTRACE_GETREGSET) ++register_reads;
    if (request==PTRACE_SETREGSET) ++register_writes;
    return ptrace(request,pid,address,data);
}
static void handler(int number,siginfo_t *info,void *context) {
    ucontext_t *uc=context;
    char stack;
    assert(number==(mode&KERNEL_CHILD ? SIGCHLD : SIGUSR1));
    assert(info->si_code==(mode&KERNEL_CHILD ? CLD_EXITED : SI_USER));
    if (mode&KERNEL_CHILD) assert(info->si_pid==12345 && info->si_status==7);
    assert(uc->uc_mcontext.pc==(uintptr_t)original_svc+(expected_restart ? 0 : 4));
    assert(expected_restart || (long)uc->uc_mcontext.regs[0]==-EINTR);
    uintptr_t at=(uintptr_t)&stack;
    assert(!!(mode&ALTSTACK)==(at>=(uintptr_t)alternate && at<(uintptr_t)alternate+sizeof(alternate)));
    shared->handled++;
    if (mode&ESCAPE) siglongjmp(escape,1);
}
static int stop(pid_t pid) {
    int status;
    /* EVENT_WAIT: exact ptraced child stop; the suite alarm bounds failure. */
    assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status));
    return status;
}
static void retry_observation(pid_t pid) {
    struct ptrace_syscall_info info={0};
    assert(ptrace(PTRACE_GET_SYSCALL_INFO,pid,(void *)sizeof(info),&info)>0);
    assert(info.op==PTRACE_SYSCALL_INFO_ENTRY);
    for (unsigned variant=0;variant<3;++variant) {
        struct md_svc_machine_thread retry={.retry_svc=info.instruction_pointer-4+(variant==1 ? 4 : 0),
            .retry_number=info.entry.nr+(variant==2)};
        struct seccomp_data call;
        memset(&call,0xa5,sizeof(call));
        struct seccomp_data untouched=call;
        assert(md_svc_machine_retry(signal_trace,&retry,pid,&call)==!variant && !retry.retry_svc);
        if (!variant) {
            assert(call.nr==(int)info.entry.nr && call.arch==info.arch
                && call.instruction_pointer==info.instruction_pointer);
            assert(!memcmp(call.args,info.entry.args,sizeof(call.args)));
        } else assert(!memcmp(&call,&untouched,sizeof(call)));
    }
}
static void run(unsigned selected,int timed) {
    mode=selected; shared->handled=0; word=0;
    expected_restart=(mode&(IGNORE|SUPPRESS)) || ((mode&RESTART) && !timed);
    int gate[2],data[2]; assert(!pipe(gate) && !pipe(data));
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        stack_t stack={.ss_sp=alternate,.ss_size=sizeof(alternate)};
        assert(!sigaltstack(&stack,NULL));
        struct sigaction action={.sa_sigaction=handler,.sa_flags=SA_SIGINFO
            |(mode&RESTART ? SA_RESTART : 0)|(mode&ALTSTACK ? SA_ONSTACK : 0)};
        sigemptyset(&action.sa_mask);
        if (mode&IGNORE) action.sa_handler=SIG_IGN;
        assert(!sigaction(mode&KERNEL_CHILD ? SIGCHLD : SIGUSR1,&action,NULL));
        if (mode&FILTER) {
            struct sock_filter instructions[]={
                BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
                BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getppid,0,1),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
            struct sock_fprog program={sizeof(instructions)/sizeof(*instructions),instructions};
            assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
            assert(!prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program));
        }
        if (mode&NATIVE_ENTRY) {
            struct sock_filter instructions[]={
                BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
                BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)((uintptr_t)alias_svc+4),0,1),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
            struct sock_fprog program={sizeof(instructions)/sizeof(*instructions),instructions};
            assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
            assert(!prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program));
        }
        char byte; assert(read(gate[0],&byte,1)==1);
        if (!sigsetjmp(escape,1)) {
            struct timespec timeout={.tv_sec=30};
            long result=timed ? alias_call(SYS_futex,(long)&word,FUTEX_WAIT_PRIVATE,0,(long)&timeout)
                : alias_call(SYS_read,data[0],(long)&byte,1,0);
            assert(!(mode&ESCAPE));
            assert(result==(expected_restart ? 1 : -EINTR));
            if (expected_restart) assert(byte=='x');
        } else assert(mode&ESCAPE);
        assert(shared->handled==((mode&(IGNORE|SUPPRESS)) ? 0 : 1));
        _exit(0);
    }
    assert(!ptrace(PTRACE_SEIZE,pid,NULL,(void *)(PTRACE_O_TRACESYSGOOD|PTRACE_O_TRACESECCOMP)));
    assert(!ptrace(PTRACE_INTERRUPT,pid,NULL,NULL)); stop(pid);
    assert(write(gate[1],"x",1)==1);
    for (;;) {
        assert(!ptrace(PTRACE_SYSCALL,pid,NULL,NULL));
        int status=stop(pid); assert(WSTOPSIG(status)==(SIGTRAP|0x80));
        struct ptrace_syscall_info info={0};
        assert(ptrace(PTRACE_GET_SYSCALL_INFO,pid,(void *)sizeof(info),&info)>0);
        if (info.op==PTRACE_SYSCALL_INFO_ENTRY && info.instruction_pointer==(uintptr_t)alias_svc+4) break;
    }
    retry_observation(pid);
    struct md_svc_site sites[1];
    struct md_svc_registry registry={.count=1,.pool_count=1,.sites=sites,
        .pools=(struct md_svc_code_pool[]){{.address=(uintptr_t)alias_svc,.used=8}}};
    registry.sites[0]=(struct md_svc_site){.direct={.gate=(uintptr_t)alias_svc,
        .svc=(uintptr_t)original_svc,.number=timed ? SYS_futex : SYS_read},.kernel_alias=(uintptr_t)alias_svc,
        .original=0xd4000001,.replacement=0xd4000001,.state=MD_SVC_PUBLISHED};
    if (mode&NATIVE_ENTRY) {
        assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
        int status=stop(pid); assert((unsigned)status>>16==PTRACE_EVENT_SECCOMP);
        struct user_pt_regs before;
        struct iovec io={&before,sizeof(before)};
        assert(!ptrace(PTRACE_GETREGSET,pid,(void *)NT_PRSTATUS,&io));
        struct user_pt_regs after=before;
        registry.disabled=1;
        assert(!md_svc_machine_native_entry(&registry,after.pc,after.regs[8]) && !memcmp(&before,&after,sizeof(before)));
        registry.disabled=0; after.regs[8]^=1;
        assert(md_svc_machine_native_entry(&registry,after.pc,after.regs[8])==2);
        registry.sites[0].state=MD_SVC_RETIRED;
        assert(!md_svc_machine_native_entry(&registry,after.pc,after.regs[8]));
        registry.sites[0].state=MD_SVC_PUBLISHED;
        after=before; ++after.pc;
        assert(!md_svc_machine_native_entry(&registry,after.pc,after.regs[8]));
        after=before;
        assert(md_svc_machine_native_entry(&registry,after.pc,after.regs[8])==1);
        assert(!memcmp(&before,&after,sizeof(before)));
    }
    if (mode&QUIET_RETURN) {
        struct md_svc_machine_thread machine={0};
        assert(!md_svc_machine_drain(signal_trace,&registry,&machine,pid,NULL,1) && machine.draining);
        assert(write(data[1],"x",1)==1);
        assert(!ptrace(PTRACE_SYSCALL,pid,NULL,NULL));
        int status=stop(pid); assert(WSTOPSIG(status)==(SIGTRAP|0x80));
        assert(!md_svc_machine_flush(signal_trace,&registry,&machine,pid) && !machine.draining);
        struct user_pt_regs returned;
        struct iovec io={&returned,sizeof(returned)};
        assert(!ptrace(PTRACE_GETREGSET,pid,(void *)NT_PRSTATUS,&io));
        assert(returned.pc==(uintptr_t)original_svc+4 && returned.regs[0]==1);
        assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
        assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
        assert(!close(gate[0]) && !close(gate[1]) && !close(data[0]) && !close(data[1]));
        return;
    }
    assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
    int signal=mode&KERNEL_CHILD ? SIGCHLD : SIGUSR1;
    assert(!kill(pid,signal));
    int status=stop(pid); assert(WSTOPSIG(status)==signal);
    if (mode&KERNEL_CHILD) {
        siginfo_t info={.si_signo=SIGCHLD,.si_code=CLD_EXITED};
        info.si_pid=12345; info.si_status=7;
        assert(!ptrace(PTRACE_SETSIGINFO,pid,NULL,&info));
    }
    struct md_svc_machine_thread machine={0};
    struct md_signal_delivery queue={0};
    int deliver=0;
    {
        struct md_task_signal_stop observation;
        md_task_signal_stop_init(&observation,signal_trace);
        info_reads=register_reads=0;
        assert(!md_task_signal_info(pid,&observation));
        assert(!md_task_signal_registers(pid,&observation));
        assert(md_svc_machine_signal(&registry,&machine,pid,&queue,&observation,&deliver)==1 && deliver==signal);
        assert(info_reads==1 && register_reads==1);
        assert(machine.signal_alias==(uintptr_t)alias_svc);
        assert(md_svc_machine_flush(signal_trace,&registry,&machine,pid)==1);
        assert(!md_svc_machine_drain(signal_trace,&registry,&machine,pid,NULL,0) && machine.draining);
        struct user_pt_regs before;
        struct iovec registers={&before,sizeof(before)};
        assert(!ptrace(PTRACE_GETREGSET,pid,(void *)NT_PRSTATUS,&registers));
        assert(before.pc==(uintptr_t)alias_svc);
        assert(!ptrace(PTRACE_CONT,pid,NULL,(void *)(intptr_t)(mode&SUPPRESS ? 0 : deliver)));
        status=stop(pid);
        assert((unsigned)status>>16==PTRACE_EVENT_STOP && !shared->handled);
        int result=md_svc_machine_signal_committed(signal_trace,&registry,&machine,pid,status);
        if (result) fprintf(stderr,"signal commit mode=%u timed=%d error=%d\n",mode,timed,result);
        assert(!result && !machine.signal_alias && !machine.draining);
        assert(write(data[1],"x",1)==1);
        assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
    }
    assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!close(gate[0]) && !close(gate[1]) && !close(data[0]) && !close(data[1]));
}
static void interrupted_native_frame(void) {
    size_t size=2*MD_SVC_POOL_SIZE;
    void *allocation=mmap(NULL,size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(allocation!=MAP_FAILED);
    uintptr_t frame=((uintptr_t)allocation+MD_SVC_POOL_SIZE-1)&~(uintptr_t)(MD_SVC_POOL_SIZE-1);
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) { for (;;) pause(); }
    assert(!ptrace(PTRACE_SEIZE,pid,NULL,NULL) && !ptrace(PTRACE_INTERRUPT,pid,NULL,NULL));
    assert((unsigned)stop(pid)>>16==PTRACE_EVENT_STOP);
    struct md_svc_site site={.state=MD_SVC_PAUSED,
        .direct={.frames=frame,.svc=0x1000,.gate=0x4000,.number=SYS_read}};
    struct md_svc_registry registry={.frames=frame,.disabled=1,.sites=&site,.count=1,
        .native={0x2000,0x2020,0x2030}};
    const struct { unsigned state; uintptr_t pc; int done; } cases[]={
        {MD_NATIVE_READY,0x9000,0},{MD_NATIVE_RUNNING,0x2000,0},
        {MD_NATIVE_RUNNING,0x2020,0},{MD_NATIVE_RUNNING,0x2024,1},
        {MD_NATIVE_RUNNING,0x2028,1},{MD_NATIVE_DONE,0x9000,1}};
    for (unsigned i=0;i<sizeof(cases)/sizeof(*cases);++i) {
        uint64_t saved[34]={0};
        for (unsigned j=0;j<31;++j) saved[j]=0xabc000+j;
        saved[8]=SYS_read; saved[31]=0xa0000000; saved[32]=0x1000000;
        uint64_t pending=1;
        struct md_entry_native native={.state=cases[i].state,.number=SYS_read,.result=17};
        assert(!md_svc_registry_write(pid,frame,&frame,8));
        assert(!md_svc_registry_write(pid,frame+MD_SVC_OWNER_OFFSET,&site.direct.gate,8));
        assert(!md_svc_registry_write(pid,frame+MD_SVC_FRAME_OFFSET,saved,sizeof(saved)));
        assert(!md_svc_registry_write(pid,frame+MD_SVC_SAVED_SP,&saved[32],8));
        assert(!md_svc_registry_write(pid,frame+MD_SVC_DIRECT_PENDING,&pending,8));
        assert(!md_svc_registry_write(pid,frame+MD_SVC_NATIVE_OFFSET,&native,sizeof(native)));
        struct user_pt_regs r={.sp=frame+MD_SVC_SLOT_SIZE-16,.pc=cases[i].pc,.pstate=0x60000000};
        r.regs[0]=cases[i].done ? 17 : saved[0];
        struct iovec io={&r,sizeof(r)};
        assert(!ptrace(PTRACE_SETREGSET,pid,(void *)NT_PRSTATUS,&io));
        struct md_svc_machine_thread machine={.draining=1};
        register_writes=0;
        assert(!md_svc_machine_flush(signal_trace,&registry,&machine,pid) && !machine.draining);
        assert(register_writes==1);
        assert(!ptrace(PTRACE_GETREGSET,pid,(void *)NT_PRSTATUS,&io));
        assert(r.sp==saved[32] && r.pc==site.direct.svc+(cases[i].done ? 4 : 0));
        assert(r.regs[0]==(cases[i].done ? 17 : saved[0]) && r.pstate==saved[31]);
        assert(!memcmp(r.regs+1,saved+1,30*8));
        assert(machine.retry_svc==(cases[i].done ? 0 : site.direct.svc));
        uint64_t busy=1;
        assert(!md_svc_registry_read(pid,frame,&busy,8) && !busy);
    }
    assert(!kill(pid,SIGKILL));
    int status; assert(waitpid(pid,&status,0)==pid && WIFSIGNALED(status));
    assert(!munmap(allocation,size));
    puts("PASS native frame interrupt: prepared, running, restarted and completed effects restore exact guest context");
}
int main(void) {
    alarm(30);
    interrupted_native_frame();
    shared=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(shared!=MAP_FAILED);
    unsigned cases=0;
    for (unsigned flags=0;flags<8;++flags) {
        run(flags,0); ++cases;
        run(flags,1); ++cases;
        run(flags|ESCAPE,0); ++cases;
        run(flags|IGNORE,0); ++cases;
        run(flags|SUPPRESS,0); ++cases;
        if (!(flags&FILTER)) {
            run(flags|NATIVE_ENTRY,0); ++cases;
            run(flags|NATIVE_ENTRY,1); ++cases;
            run(flags|NATIVE_ENTRY|ESCAPE,0); ++cases;
            run(flags|NATIVE_ENTRY|IGNORE,0); ++cases;
            run(flags|NATIVE_ENTRY|SUPPRESS,0); ++cases;
        }
    }
    run(NATIVE_ENTRY|IGNORE|QUIET_RETURN,0); ++cases;
    for (unsigned flags=0;flags<8;++flags) {
        run(flags|KERNEL_CHILD,0); ++cases;
        run(flags|KERNEL_CHILD,1); ++cases;
    }
    assert(!munmap(shared,4096));
    printf("PASS native alias signal commit: %u cases; EINTR, restart, restart-block, altstack, filter, ignored, suppressed and longjmp\n",cases);
}
