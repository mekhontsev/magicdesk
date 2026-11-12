#define _GNU_SOURCE
#include "svc_direct.h"
#include "svc_text.h"
#include "svc_frame.h"
#include <assert.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/ptrace.h>
#include <linux/seccomp.h>
#include <signal.h>
#include <setjmp.h>
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

struct frame { uint64_t x[31],nzcv,fpcr,fpsr,complete,fallback; unsigned char q[512]; };
extern unsigned char md_svc_lab_call[],md_svc_lab_context_return[],signal_syscall[];
extern void md_svc_lab_context(const struct frame *,struct frame *);
__asm__(".text\n.balign 4\n.global signal_syscall\nsignal_syscall:\nsvc #0\nnop\n");
static struct md_svc_direct_context plan;
static struct frame input,output;
static sigjmp_buf escape;
static struct shared {
    uintptr_t sp;
    unsigned effects,seen,mode,decline,busy,at_handler,unsafe;
    unsigned expected_signals;
    unsigned collisions;
    uint64_t occupied;
    struct { uintptr_t handler,flags,restorer; uint64_t mask; } action;
} *shared;
static uintptr_t sites[512];
static size_t site_count;
static unsigned deferred_count,recovered_count;
static unsigned accelerated(void) {
    return !shared->decline && !shared->busy && input.x[6]==plan.number
        && (plan.number!=SYS_mmap || !(input.x[3]&MAP_ANONYMOUS) || (input.x[3]&MAP_FIXED));
}
static uint64_t native_result(void) {
    return (uint32_t)input.x[6]==SYS_mmap ? (uint64_t)-EINVAL
        : (uint32_t)input.x[6]==SYS_getppid ? getppid() : getpid();
}
static int remote(void *opaque,uintptr_t at,void *data,size_t size,int write) {
    if (write && shared->unsafe==2 && at%MD_SVC_SLOT_SIZE==MD_SVC_DIRECT_PENDING
            && size==8 && *(uint64_t *)data==1) return 0;
    pid_t pid=(pid_t)(intptr_t)opaque;
    struct iovec local={data,size},other={(void *)at,size};
    ssize_t n=write ? process_vm_writev(pid,&local,1,&other,1,0) : process_vm_readv(pid,&local,1,&other,1,0);
    return n==(ssize_t)size ? 0 : n<0 ? -errno : -EIO;
}
static void registers(pid_t pid,int op,struct user_pt_regs *r) {
    struct iovec io={r,sizeof(*r)}; assert(!ptrace(op,pid,(void *)NT_PRSTATUS,&io));
}
static int wait_stop(pid_t pid,int sig) {
    int status; assert(waitpid(pid,&status,0)==pid);
    if (!WIFSTOPPED(status) || WSTOPSIG(status)!=sig) {
        fprintf(stderr,"signal fixture pid=%d expectedStop=%d status=%#x\n",pid,sig,status);
        abort();
    }
    return status;
}
static int callback(uint64_t *r,void *unused) {
    (void)unused;
    if (shared->decline) return 0;
    ++shared->effects;
    r[0]=0x4321;
    return 1;
}
static void unexpected(int number) { (void)number; _exit(81); }
static void received(int number,siginfo_t *info,void *context) {
    ucontext_t *u=context;
    uint64_t expected[31]; memcpy(expected,input.x,sizeof(expected));
    expected[7]=0; expected[8]=input.x[6]; expected[30]=(uintptr_t)md_svc_lab_context_return;
    if (shared->effects) expected[0]=0x4321;
    int signal=(shared->mode&4) ? SIGRTMIN+4 : SIGUSR1;
    if (number!=signal || info->si_pid!=getppid()
            || info->si_code!=((shared->mode&4) ? SI_QUEUE : SI_TKILL)
            || ((shared->mode&4) && info->si_value.sival_int!=0x123abc+
                ((shared->mode&32) ? (int)shared->seen : 0))) _exit(82);
    if (!(shared->mode&32) || !shared->seen) {
        if (u->uc_mcontext.pc!=plan.svc+(shared->effects ? 4 : 0)
            || u->uc_mcontext.sp!=shared->sp
            || memcmp(expected,u->uc_mcontext.regs,sizeof(expected))
            || (u->uc_mcontext.pstate&0xf0000000)!=input.nzcv
            || !!sigismember(&u->uc_sigmask,SIGTRAP)!=!!(shared->mode&1)) _exit(82);
    }
    if ((shared->mode&32) && (u->uc_mcontext.sp>plan.frames
            && u->uc_mcontext.sp<=plan.frames+MD_SVC_POOL_SIZE)) _exit(87);
    struct fpsimd_context *fp=(void *)u->uc_mcontext.__reserved;
    if (!(shared->mode&32) || !shared->seen)
        if (fp->head.magic!=FPSIMD_MAGIC || fp->fpsr!=input.fpsr || fp->fpcr!=input.fpcr
                || memcmp(fp->vregs,input.q,sizeof(input.q))) _exit(83);
    ++shared->seen;
    shared->at_handler=shared->effects;
    if (shared->mode&32) {
        if ((shared->mode&16) && shared->seen==1) siglongjmp(escape,1);
        return;
    }
    if (shared->mode&8) {
        struct frame nested;
        md_svc_lab_context(&input,&nested);
        if (nested.x[0]!=(accelerated() ? 0x4321 : native_result())
                || shared->effects!=shared->at_handler+accelerated()) _exit(86);
    }
    if (shared->mode&16) siglongjmp(escape,1);
    /* An edited continuation must neither replay the completed effect nor
     * retain a private frame after returning from the handler. */
    u->uc_mcontext.pc=plan.svc+4;
    u->uc_mcontext.regs[0]=0x7777;
    u->uc_mcontext.regs[19]=0x8888;
    u->uc_mcontext.pstate=(u->uc_mcontext.pstate&~UINT64_C(0xf0000000))|UINT64_C(0x60000000);
    fp->vregs[3]^=1;
}
static void checkpoint_filter(void) {
    uintptr_t ip=md_svc_direct_checkpoint(&plan)+4;
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)(ip>>32),0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)ip,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|1234),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog p={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&p));
}
static void child(int injected) {
    struct sigaction a={.sa_sigaction=received,.sa_flags=SA_SIGINFO|SA_ONSTACK};
    sigemptyset(&a.sa_mask); assert(!sigaction(SIGUSR1,&a,NULL));
    assert(!sigaction(SIGRTMIN+4,&a,NULL));
    a.sa_flags=0; a.sa_handler=unexpected; assert(!sigaction(SIGTRAP,&a,NULL));
    void *stack=mmap(NULL,65536,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(stack!=MAP_FAILED);
    stack_t alt={.ss_sp=stack,.ss_size=65536}; assert(!sigaltstack(&alt,NULL));
    if (shared->busy) for (unsigned i=0;i<MD_SVC_SLOT_COUNT;++i)
        *(uint64_t *)(plan.frames+i*MD_SVC_SLOT_SIZE)=1;
    checkpoint_filter();
    assert(!ptrace(PTRACE_TRACEME,0,NULL,NULL) && !raise(SIGSTOP));
    int escaped=sigsetjmp(escape,1);
    if (!escaped) md_svc_lab_context(&input,&output);
    if (shared->mode&32) {
        sigset_t mask; sigemptyset(&mask);
        /* EVENT_WAIT: queued RT delivery completes; alarm bounds lost signals. */
        while (shared->seen<shared->expected_signals) sigsuspend(&mask);
    }
    struct frame expected=input;
    expected.x[7]=0; expected.x[8]=input.x[6]; expected.x[30]=(uintptr_t)md_svc_lab_context_return;
    expected.x[0]=accelerated() ? 0x4321 : native_result();
    if (injected) {
        if (!shared->seen) _exit(84);
        assert(shared->seen==((shared->mode&32) ? shared->expected_signals : 1) && shared->at_handler<=1);
        assert(shared->effects==shared->at_handler+((shared->mode&8) && accelerated()));
        if (!(shared->mode&32)) {
            expected.x[0]=0x7777; expected.x[19]=0x8888; expected.nzcv=UINT64_C(0x60000000);
            expected.q[3*16]^=1;
        }
    } else assert(!shared->seen && shared->effects==accelerated());
    if (!escaped) assert(!memcmp(&expected,&output,sizeof(expected)));
    for (unsigned i=0;i<MD_SVC_SLOT_COUNT;++i) {
        assert(!!*(uint64_t *)(plan.frames+i*MD_SVC_SLOT_SIZE)==!!(shared->occupied&(UINT64_C(1)<<i)));
        assert(!*(uint64_t *)(plan.frames+i*MD_SVC_SLOT_SIZE+MD_SVC_DIRECT_PENDING));
    }
    sigset_t mask; assert(!sigprocmask(SIG_SETMASK,NULL,&mask));
    assert(!!sigismember(&mask,SIGTRAP)==!!(!escaped && (shared->mode&1)));
    assert(!sigaction(SIGTRAP,NULL,&a) && a.sa_handler==((shared->mode&2) ? SIG_IGN : unexpected));
    _exit(0);
}
static void disposition(pid_t pid) {
    /* Test instrumentation only: change SIGTRAP after single-step reaches
     * the selected boundary. The tested owner never changes dispositions. */
    if (shared->mode&2) {
        struct user_pt_regs saved,r; registers(pid,PTRACE_GETREGSET,&saved); r=saved;
        r.pc=(uintptr_t)signal_syscall; r.regs[8]=SYS_rt_sigaction;
        r.regs[0]=SIGTRAP; r.regs[1]=(uintptr_t)&shared->action; r.regs[2]=0; r.regs[3]=8;
        registers(pid,PTRACE_SETREGSET,&r);
        assert(!ptrace(PTRACE_SYSCALL,pid,NULL,NULL)); wait_stop(pid,SIGTRAP|0x80);
        assert(!ptrace(PTRACE_SYSCALL,pid,NULL,NULL)); wait_stop(pid,SIGTRAP|0x80);
        registers(pid,PTRACE_GETREGSET,&r); assert(!r.regs[0]);
        registers(pid,PTRACE_SETREGSET,&saved);
    }
    uint64_t mask=(shared->mode&1) ? UINT64_C(1)<<(SIGTRAP-1) : 0;
    assert(!ptrace(PTRACE_SETSIGMASK,pid,(void *)sizeof(mask),&mask));
}
static int in_gate(uintptr_t pc) {
    return pc>=plan.gate && pc<plan.gate+md_svc_direct_signal_size();
}
static int selected_signal(void) { return (shared->mode&4) ? SIGRTMIN+4 : SIGUSR1; }
static void queue_signal(pid_t pid) {
    if (shared->mode&4) {
        siginfo_t info={.si_signo=selected_signal(),.si_code=SI_QUEUE};
        info.si_pid=getpid(); info.si_uid=getuid(); info.si_value.sival_int=0x123abc;
        assert(!syscall(SYS_rt_tgsigqueueinfo,pid,pid,selected_signal(),&info));
    } else assert(!syscall(SYS_tgkill,pid,pid,SIGUSR1));
}
static void exercise(uintptr_t target,unsigned mode) {
    shared->sp=0; shared->effects=0; shared->seen=0; shared->at_handler=0; shared->mode=0;
    shared->occupied=shared->busy ? UINT64_MAX : 0;
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) child(target!=0);
    wait_stop(pid,SIGSTOP);
    assert(!ptrace(PTRACE_SETOPTIONS,pid,NULL,(void *)(PTRACE_O_TRACESYSGOOD|PTRACE_O_TRACESECCOMP|PTRACE_O_EXITKILL)));
    int status=0,injected=0,pending=0;
    siginfo_t held={0};
    for (unsigned steps=0;steps<10000;++steps) {
        struct user_pt_regs r; registers(pid,PTRACE_GETREGSET,&r);
        if (r.pc==plan.svc && !shared->sp) {
            shared->sp=r.sp;
            unsigned first=((r.sp^(r.sp>>16))>>8)&(MD_SVC_SLOT_COUNT-1);
            for (unsigned i=0;i<shared->collisions;++i) {
                unsigned slot=first^MD_SVC_SLOT_XOR(i);
                uint64_t claim=plan.frames+slot*MD_SVC_SLOT_SIZE;
                assert(!remote((void *)(intptr_t)pid,claim,&claim,sizeof(claim),1));
                shared->occupied|=UINT64_C(1)<<slot;
            }
        }
        if (!target && (in_gate(r.pc) || (r.sp>plan.frames && r.sp<=plan.frames+MD_SVC_POOL_SIZE))) {
            size_t i=0; while (i<site_count && sites[i]!=r.pc) ++i;
            if (i==site_count) { assert(site_count<sizeof(sites)/sizeof(*sites)); sites[site_count++]=r.pc; }
        }
        if (!injected && target && r.pc==target) {
            shared->mode=mode; disposition(pid); injected=1;
            queue_signal(pid); assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
            wait_stop(pid,selected_signal()); assert(!ptrace(PTRACE_GETSIGINFO,pid,NULL,&held));
            registers(pid,PTRACE_GETREGSET,&r);
            int result=shared->unsafe==1 ? 1 : md_svc_direct_recover(&plan,&r,remote,(void *)(intptr_t)pid);
            assert(result==1 || result==2);
            pending=result==2;
            if (pending) ++deferred_count;
            else { ++recovered_count; registers(pid,PTRACE_SETREGSET,&r); }
            assert(!ptrace(PTRACE_CONT,pid,NULL,(void *)(intptr_t)(pending ? 0 : selected_signal())));
        } else assert(!ptrace(injected ? PTRACE_CONT : PTRACE_SINGLESTEP,pid,NULL,NULL));
        assert(waitpid(pid,&status,0)==pid);
        if (!WIFSTOPPED(status)) break;
        if ((unsigned)status>>16==PTRACE_EVENT_SECCOMP) {
            assert(pending);
            registers(pid,PTRACE_GETREGSET,&r);
            assert(r.pc==md_svc_direct_checkpoint(&plan)+4);
            assert(md_svc_direct_recover(&plan,&r,remote,(void *)(intptr_t)pid)==1);
            int nr=-1; struct iovec io={&nr,sizeof(nr)};
            assert(!ptrace(PTRACE_SETREGSET,pid,(void *)NT_ARM_SYSTEM_CALL,&io));
            assert(!ptrace(PTRACE_SYSCALL,pid,NULL,NULL)); wait_stop(pid,SIGTRAP|0x80);
            registers(pid,PTRACE_SETREGSET,&r);
            queue_signal(pid); assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
            wait_stop(pid,selected_signal());
            assert(!ptrace(PTRACE_SETSIGINFO,pid,NULL,&held));
            assert(!ptrace(PTRACE_CONT,pid,NULL,(void *)(intptr_t)selected_signal()));
            assert(waitpid(pid,&status,0)==pid); pending=0;
            assert(!WIFSTOPPED(status)); break;
        }
        if (injected || WSTOPSIG(status)!=SIGTRAP) {
            fprintf(stderr,"unexpected signal target=%#lx mode=%u stop=%#x\n",(unsigned long)target,mode,status);
            abort();
        }
    }
    if (shared->unsafe) {
        assert(WIFEXITED(status) && WEXITSTATUS(status)==(shared->unsafe==1 ? 82 : 84));
        printf("PASS negative signal control: kind=%u childExit=%d\n",shared->unsafe,WEXITSTATUS(status));
        return;
    }
    if (!WIFEXITED(status) || WEXITSTATUS(status) || (target && !injected) || pending) {
        fprintf(stderr,"failed target=%#lx offset=%ld mode=%u status=%#x effect=%u seen=%u\n",
            (unsigned long)target,(long)(target-plan.gate),mode,status,shared->effects,shared->seen);
        abort();
    }
}
static void queued_signal(pid_t pid,int value) {
    siginfo_t info={.si_signo=SIGRTMIN+4,.si_code=SI_QUEUE};
    info.si_pid=getpid(); info.si_uid=getuid(); info.si_value.sival_int=value;
    assert(!syscall(SYS_rt_tgsigqueueinfo,pid,pid,info.si_signo,&info));
}
static void exercise_queue(unsigned mode,int unsafe) {
    enum { HELD=8, TOTAL=HELD+1, CARRIER=0x76543210 };
    shared->sp=0; shared->effects=0; shared->seen=0; shared->at_handler=0;
    shared->mode=0; shared->decline=0; shared->busy=0; shared->expected_signals=TOTAL;
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) child(1);
    wait_stop(pid,SIGSTOP);
    assert(!ptrace(PTRACE_SETOPTIONS,pid,NULL,(void *)(PTRACE_O_TRACESYSGOOD|PTRACE_O_TRACESECCOMP|PTRACE_O_EXITKILL)));
    struct user_pt_regs r;
    for (unsigned steps=0;;++steps) {
        assert(steps<10000);
        registers(pid,PTRACE_GETREGSET,&r);
        if (r.pc==plan.svc && !shared->sp) shared->sp=r.sp;
        if (r.pc==(uintptr_t)callback) break;
        assert(!ptrace(PTRACE_SINGLESTEP,pid,NULL,NULL)); wait_stop(pid,SIGTRAP);
    }
    shared->mode=mode|32|4; disposition(pid);
    for (unsigned i=0;i<HELD;++i) queued_signal(pid,0x123abc+(int)i);
    siginfo_t held[TOTAL];
    unsigned count=0,delivered=0;
    int status;
    for (;;) {
        assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
        assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status));
        if ((unsigned)status>>16==PTRACE_EVENT_SECCOMP) break;
        assert(WSTOPSIG(status)==SIGRTMIN+4 && count<HELD);
        assert(!ptrace(PTRACE_GETSIGINFO,pid,NULL,&held[count]));
        assert(held[count].si_value.sival_int==0x123abc+(int)count); ++count;
        registers(pid,PTRACE_GETREGSET,&r);
        assert(md_svc_direct_recover(&plan,&r,remote,(void *)(intptr_t)pid)==2);
    }
    assert(count==HELD);
    registers(pid,PTRACE_GETREGSET,&r);
    assert(r.pc==md_svc_direct_checkpoint(&plan)+4);
    assert(md_svc_direct_recover(&plan,&r,remote,(void *)(intptr_t)pid)==1);
    int nr=-1; struct iovec io={&nr,sizeof(nr)};
    assert(!ptrace(PTRACE_SETREGSET,pid,(void *)NT_ARM_SYSTEM_CALL,&io));
    assert(!ptrace(PTRACE_SYSCALL,pid,NULL,NULL)); wait_stop(pid,SIGTRAP|0x80);
    registers(pid,PTRACE_SETREGSET,&r);
    /* A newer real signal can already precede requeued notifications. The
     * delivery owner must restore FIFO, not merely resubmit the held siginfo. */
    queued_signal(pid,0x123abc+HELD);
    for (unsigned i=0;i<HELD;++i) queued_signal(pid,unsafe ? 0x123abc+(int)i : CARRIER);
    int deliver=0;
    for (;;) {
        assert(!ptrace(PTRACE_CONT,pid,NULL,(void *)(intptr_t)deliver));
        assert(waitpid(pid,&status,0)==pid);
        if (!WIFSTOPPED(status)) break;
        assert(WSTOPSIG(status)==SIGRTMIN+4 && !((unsigned)status>>16));
        siginfo_t info; assert(!ptrace(PTRACE_GETSIGINFO,pid,NULL,&info));
        if (!unsafe) {
            if (info.si_value.sival_int!=CARRIER) {
                assert(count<TOTAL); held[count++]=info;
            }
            assert(delivered<count);
            assert(!ptrace(PTRACE_SETSIGINFO,pid,NULL,&held[delivered++]));
        }
        deliver=SIGRTMIN+4;
    }
    assert(WIFEXITED(status) && WEXITSTATUS(status)==(unsafe ? 82 : 0));
    if (!unsafe) assert(count==TOTAL && delivered==TOTAL && shared->effects==1);
    printf("PASS %squeued RT: mode=%u sent=%u delivered=%u lateArrival=1 effects=once\n",
        unsafe ? "negative " : "",mode,TOTAL,delivered);
}
int main(int argc,char **argv) {
    assert(argc==1 || (argc==2 && (!strcmp(argv[1],"--unsafe-context")
        || !strcmp(argv[1],"--unsafe-checkpoint") || !strcmp(argv[1],"--queued-unsafe")
        || !strcmp(argv[1],"--queued") || !strcmp(argv[1],"--mmap-guard"))));
    int map_guard=argc==2 && !strcmp(argv[1],"--mmap-guard");
    alarm(240);
    size_t page=(size_t)getpagesize();
    shared=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0); assert(shared!=MAP_FAILED);
    shared->action.handler=(uintptr_t)SIG_IGN;
    void *frames=mmap((void *)MD_SVC_POOL_BASE,MD_SVC_POOL_SIZE,PROT_READ|PROT_WRITE,
        MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED_NOREPLACE,-1,0); assert(frames==(void *)MD_SVC_POOL_BASE);
    for (unsigned i=0;i<MD_SVC_SLOT_COUNT;++i)
        assert(!mprotect((char *)frames+i*MD_SVC_SLOT_SIZE+MD_SVC_GUARD_OFFSET,MD_SVC_GUARD_SIZE,PROT_NONE));
    void *code=mmap((void *)0x3000000,page,PROT_READ|PROT_WRITE,
        MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED_NOREPLACE,-1,0); assert(code==(void *)0x3000000);
    plan=(struct md_svc_direct_context){.svc=(uintptr_t)md_svc_lab_call,.gate=(uintptr_t)code,
        .frames=MD_SVC_POOL_BASE,.number=map_guard ? SYS_mmap : SYS_getpid,
        .checkpoint=(uintptr_t)md_svc_signal_checkpoint};
    uintptr_t cold=(uintptr_t)code+2048;
    assert(!md_svc_direct_kernel(plan.svc,cold,(uint32_t *)cold));
    struct md_svc_direct_fallback exits={cold,cold,cold}; uint32_t branch;
    assert(!md_svc_direct_signal_plan(&plan,(uintptr_t)callback,exits,code,page,&branch));
    __builtin___clear_cache(code,(char *)code+page); assert(!mprotect(code,page,PROT_READ|PROT_EXEC));
    int mem=open("/proc/self/mem",O_RDWR|O_CLOEXEC); assert(mem>=0);
    uintptr_t text=plan.svc&~(uintptr_t)(page-1);
    struct md_svc_text mirror={.fd=-1};
    assert(!md_svc_text_install(&mirror,mem,text,text,page));
    assert(!md_svc_text_publish(&mirror,plan.svc,branch));
    for (unsigned i=0;i<31;++i) input.x[i]=0xabc00000+i;
    input.x[6]=SYS_getpid; input.nzcv=UINT64_C(0xa0000000);
    input.fpcr=UINT64_C(1)<<22; input.fpsr=UINT64_C(1)<<4;
    for (unsigned i=0;i<sizeof(input.q);++i) input.q[i]=(unsigned char)(i*17+31);
    if (map_guard) {
        input.x[6]=SYS_mmap; input.x[1]=0;
        unsigned cases=0;
        for (unsigned mask=0;mask<4;++mask) {
            input.x[3]=MAP_PRIVATE|((mask&1) ? MAP_FIXED : 0)|((mask&2) ? MAP_ANONYMOUS : 0);
            site_count=0; exercise(0,0);
            for (size_t i=0;i<site_count;++i) if (sites[i]>=plan.gate && sites[i]<plan.gate+256)
                for (unsigned behavior=0;behavior<4;++behavior) {
                    exercise(sites[i],behavior); ++cases;
                }
        }
        assert(cases && recovered_count);
        printf("PASS mmap argument guard: cases=%u exact PC, GPRs, flags and signals\n",cases);
        return 0;
    }
    if (argc==2 && !strcmp(argv[1],"--queued-unsafe")) { exercise_queue(3,1); return 0; }
    if (argc==2 && !strcmp(argv[1],"--queued")) {
        for (unsigned mode=0;mode<4;++mode) { exercise_queue(mode,0); exercise_queue(mode|16,0); }
        return 0;
    }
    if (argc==2) {
        shared->unsafe=!strcmp(argv[1],"--unsafe-context") ? 1 : 2;
        exercise(shared->unsafe==1 ? plan.gate : (uintptr_t)callback,3);
        return 0;
    }
    unsigned cases=0;
    for (unsigned path=0;path<5+MD_SVC_SLOT_ALTERNATIVES-1;++path) {
        shared->decline=path==1; shared->busy=path==2;
        shared->collisions=path>=5 ? path-4 : 0;
        input.x[6]=path==3 ? SYS_getppid : path==4 ? (UINT64_C(1)<<32)|SYS_getpid : SYS_getpid;
        site_count=0; exercise(0,0);
        const unsigned behaviors[]={0,4,8,20};
        for (size_t i=0;i<site_count;++i) for (unsigned behavior=0;behavior<4;++behavior)
            for (unsigned mask=0;mask<4;++mask) {
                exercise(sites[i],behaviors[behavior]|mask); ++cases;
            }
        printf("SVC_SIGNAL_PATH path=%u instructions=%zu\n",path,site_count); fflush(stdout);
    }
    assert(deferred_count && recovered_count);
    printf("PASS SVC arbitrary boundary: cases=%u recovered=%u deferred=%u blocked/ignoredTRAP=1 realtime=1 nested=1 longjmp=1 editedContext=1 SIMD=1 effects=once frames=released\n",
        cases,recovered_count,deferred_count);
}
