#define _GNU_SOURCE
#include "svc_machine.h"
#include "signal_protocol.h"
#include "mapping_reader.h"
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <sys/ptrace.h>
#include <linux/ptrace.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <ucontext.h>
#include <unistd.h>

static int regs(md_svc_trace_fn trace,pid_t pid,int operation,struct user_pt_regs *r) {
    struct iovec io={r,sizeof(*r)};
    return trace(operation,pid,(void *)NT_PRSTATUS,&io)<0 ? -errno : 0;
}
static int memory(void *opaque,uintptr_t at,void *data,size_t size,int write) {
    pid_t pid=(pid_t)(intptr_t)opaque;
    return write ? md_svc_registry_write(pid,at,data,size) : md_svc_registry_read(pid,at,data,size);
}
struct md_svc_registry *md_svc_machine_admit(pid_t pid,uintptr_t descriptor) {
    struct md_svc_runtime runtime;
    if (!descriptor || md_svc_registry_read(pid,descriptor,&runtime,sizeof(runtime))
            || !runtime.count || runtime.count>MD_SVC_CODE_POOLS || runtime.error) return NULL;
    struct md_svc_registry *r=md_svc_registry_new(runtime.frames,runtime.dispatch,runtime.checkpoint);
    if (!r) return NULL;
    uintptr_t cursor[MD_SVC_CODE_POOLS];
    for (unsigned i=0;i<runtime.count;++i) {
        if (md_svc_registry_pool(r,runtime.pools[i])) { md_svc_registry_free(r); return NULL; }
        cursor[i]=runtime.pools[i];
    }
    char path[64]; snprintf(path,sizeof(path),"/proc/%d/maps",pid);
    int fd=open(path,O_RDONLY|O_CLOEXEC);
    if (fd<0) { md_svc_registry_free(r); return NULL; }
    struct md_mapping_reader reader={.fd=fd}; struct md_mapping_record v;
    int result;
    /* Admit all resident ranges from one walk, retaining neither a PID cache
     * nor pathname-derived authority after this stopped-task observation. */
    while ((result=md_mapping_next(&reader,&v))>0) {
        if (v.shared || (v.protection&(PROT_WRITE|PROT_EXEC))!=PROT_EXEC) continue;
        if (v.first<=runtime.dispatch && runtime.dispatch<v.end) {
            r->native_begin=v.first; r->native_end=v.end;
        }
        for (unsigned i=0;i<runtime.count;++i) {
            uintptr_t end=runtime.pools[i]+MD_SVC_CODE_BYTES;
            if (cursor[i]<end && v.first<=cursor[i] && cursor[i]<v.end)
                cursor[i]=v.end<end ? v.end : end;
        }
    }
    close(fd);
    for (unsigned i=0;i<runtime.count;++i)
        if (cursor[i]!=runtime.pools[i]+MD_SVC_CODE_BYTES) { md_svc_registry_free(r); return NULL; }
    if (result<0 || !r->native_end || runtime.checkpoint<r->native_begin
            || runtime.checkpoint>r->native_end-8 || runtime.native.begin<r->native_begin
            || runtime.native.call<runtime.native.begin || runtime.native.call>=runtime.native.end
            || runtime.native.end>=r->native_end) { md_svc_registry_free(r); return NULL; }
    r->native=runtime.native;
    return r;
}
void md_svc_machine_forget(struct md_svc_machine_thread *s) {
    *s=(struct md_svc_machine_thread){0};
}
int md_svc_machine_unwind(struct md_svc_registry *r,struct md_svc_machine_thread *s,
        pid_t pid,struct user_pt_regs *registers) {
    if (!r || s->syscall_exit || s->signal_alias) return -EINVAL;
    struct md_svc_site *site=md_svc_registry_direct_at(r,pid,registers->pc,registers->sp);
    if (!site) return -EPROTO;
    int error=md_svc_direct_unwind(&site->direct,registers,memory,(void *)(intptr_t)pid);
    if (!error) s->draining=0;
    return error;
}
int md_svc_machine_retire(md_svc_trace_fn trace,struct md_svc_registry *r,pid_t pid,const struct user_pt_regs *adapter) {
    if (!md_svc_registry_resident(r)) return 0;
    struct user_pt_regs registers;
    int error=regs(trace,pid,PTRACE_GETREGSET,&registers);
    if (error) return error;
    struct md_svc_site *site=md_svc_registry_direct_at(r,pid,registers.pc,registers.sp);
    if (!site && adapter) {
        registers=*adapter;
        site=md_svc_registry_direct_at(r,pid,registers.pc,registers.sp);
    }
    if (!site) return 0;
    int result=md_svc_direct_recover(&site->direct,&registers,memory,(void *)(intptr_t)pid);
    if (result!=2) return result<0 ? result : 0;
    /* Recovery normally drains a running payload. At an exit stop it cannot
     * run again; the validated frame belongs only to this terminal task. */
    uintptr_t frame=(registers.sp-1)&~(uintptr_t)(MD_SVC_SLOT_SIZE-1);
    uint64_t zero=0;
    error=md_svc_registry_write(pid,frame+MD_SVC_DIRECT_PENDING,&zero,sizeof(zero));
    if (!error) error=md_svc_registry_write(pid,frame,&zero,sizeof(zero));
    return error;
}
static int retry(struct md_svc_registry *r,struct md_svc_machine_thread *s,pid_t pid,
        const struct user_pt_regs *registers) {
    int error=md_svc_registry_suspend(r,pid,registers->pc);
    if (!error) {
        s->retry_svc=registers->pc;
        s->retry_number=registers->regs[8];
    }
    return error;
}
static int recover(struct md_svc_registry *r,struct md_svc_machine_thread *s,pid_t pid,
        struct user_pt_regs *registers,int interrupted) {
    struct md_svc_site *site=md_svc_registry_direct_at(r,pid,registers->pc,registers->sp);
    if (!site) return 0;
    if (registers->pc==site->kernel_alias) {
        registers->pc+=4;
        return md_svc_machine_return(r,s,pid,registers);
    } else if (registers->pc==site->kernel_alias+4) {
        registers->pc=site->direct.svc+4;
        s->draining=0;
        return 1;
    }
    int result=interrupted ? md_svc_direct_native_recover(&site->direct,&r->native,
        registers,memory,(void *)(intptr_t)pid) : 0;
    if (result==MD_SVC_NATIVE_RESTART) {
        /* EVENT_STOP follows the kernel's restart decision: unlike a signal
         * delivery stop, it no longer needs the native gate's original PC. */
        registers->pc=site->direct.svc;
        result=1;
    }
    if (!result) result=md_svc_direct_recover(&site->direct,registers,memory,(void *)(intptr_t)pid);
    if (result==1 && registers->pc==site->direct.svc) {
        int error=retry(r,s,pid,registers);
        if (error) return error;
    }
    if (result==1) s->draining=0;
    return result;
}
int md_svc_machine_drain(md_svc_trace_fn trace,struct md_svc_registry *r,struct md_svc_machine_thread *s,pid_t pid,
        const struct user_pt_regs *adapter,int syscall_stop) {
    /* This stop already owns a reconstructed guest continuation. The exit
     * handler installs it before the filter fence permits publication. */
    if (s->syscall_exit) return 0;
    /* A filter/dumpability fence must leave the kernel's pending restart
     * decision untouched and wait for the signal-commit boundary. */
    if (s->signal_alias) { s->draining=1; return 0; }
    struct user_pt_regs registers;
    if (adapter) registers=*adapter;
    else {
        int error=regs(trace,pid,PTRACE_GETREGSET,&registers);
        if (error) return error;
    }
    struct md_svc_site *site=md_svc_registry_direct_at(r,pid,registers.pc,registers.sp);
    if (!site) return 0;
    if (adapter || syscall_stop) {
        if (registers.sp>r->frames && registers.sp<=r->frames+MD_SVC_POOL_SIZE) {
            uintptr_t frame=(registers.sp-1)&~(uintptr_t)(MD_SVC_SLOT_SIZE-1);
            uint64_t one=1;
            int error=md_svc_registry_write(pid,frame+MD_SVC_DIRECT_PENDING,&one,8);
            if (error) return error;
            s->draining=1;
        } else if (!adapter) s->draining=1;
        return 0;
    }
    int result=recover(r,s,pid,&registers,0);
    if (result==2) s->draining=1;
    if (result==1) return regs(trace,pid,PTRACE_SETREGSET,&registers);
    return result<0 ? result : 0;
}
int md_svc_machine_flush(md_svc_trace_fn trace,struct md_svc_registry *r,struct md_svc_machine_thread *s,pid_t pid) {
    if (!r) return 0;
    /* Finish the checkpoint before restoring the guest continuation and
     * returning retained signals to their original kernel delivery context. */
    if (s->syscall_exit || s->signal_alias) return 1;
    struct user_pt_regs registers;
    int error=regs(trace,pid,PTRACE_GETREGSET,&registers);
    if (error) return error;
    int interrupted=0;
    if (registers.sp>r->frames && registers.sp<=r->frames+MD_SVC_POOL_SIZE) {
        siginfo_t info;
        if (trace(PTRACE_GETSIGINFO,pid,NULL,&info)<0) return -errno;
        interrupted=info.si_signo==SIGTRAP && info.si_code==(SIGTRAP|(PTRACE_EVENT_STOP<<8));
    }
    int recovery=recover(r,s,pid,&registers,interrupted);
    if (recovery<0) return recovery;
    if (recovery==1 && (error=regs(trace,pid,PTRACE_SETREGSET,&registers))) return error;
    if (!recovery) s->draining=0;
    return recovery==2;
}
int md_svc_machine_signal(struct md_svc_registry *r,struct md_svc_machine_thread *s,pid_t pid,
        struct md_signal_delivery *queue,struct md_task_signal_stop *stop,int *deliver) {
    md_svc_trace_fn trace=stop->trace;
    int error=md_task_signal_info(pid,stop);
    if (error) return error==-EINVAL ? 0 : error;
    siginfo_t info=stop->info;
    int signal=info.si_signo;
    if (signal<=0 || signal>=NSIG) return 0;
    if (signal==SIGSTOP || signal==SIGKILL || md_signal_synchronous(&info))
        return 0;
    error=md_task_signal_registers(pid,stop);
    if (error) return error;
    struct user_pt_regs registers=stop->regs;
    struct md_svc_site *site=md_svc_registry_direct_at(r,pid,registers.pc,registers.sp);
    if (site) {
        int native=md_svc_direct_native_recover(&site->direct,&r->native,&registers,memory,(void *)(intptr_t)pid);
        if (native<0) return native;
        if (native) {
            if (native==MD_SVC_NATIVE_RESTART) {
                /* Keep the kernel's restart PC until do_signal has applied
                 * SA_RESTART, but construct the handler on the guest stack. */
                if (trace(PTRACE_INTERRUPT,pid,NULL,NULL)<0) return -errno;
                s->signal_alias=r->native.call; s->signal_svc=site->direct.svc;
                s->signal_sp=registers.sp;
            } else if (registers.pc==site->direct.svc && (error=retry(r,s,pid,&registers))) return error;
            error=regs(trace,pid,PTRACE_SETREGSET,&registers);
            if (error) return error;
            *deliver=signal; return 1;
        }
    }
    if (site && registers.pc==site->kernel_alias) {
        /* EVENT_WAIT: do_signal must compare its original restart PC before
         * we translate it. INTERRUPT stops after that decision and signal-frame
         * construction, before any handler instruction. Only interrupted native
         * aliases need this boundary, not ordinary syscalls or accelerated calls. */
        if (trace(PTRACE_INTERRUPT,pid,NULL,NULL)<0) return -errno;
        s->signal_alias=site->kernel_alias; s->signal_svc=site->direct.svc;
        s->signal_sp=registers.sp;
        *deliver=signal; return 1;
    }
    int recovery=recover(r,s,pid,&registers,0);
    if (recovery<0) return recovery;
    if (recovery==2) {
        error=md_signal_delivery_retain(queue,pid,&info);
        if (error<0) return error;
        *deliver=error; return 1;
    }
    if (recovery==1 && (error=regs(trace,pid,PTRACE_SETREGSET,&registers))) return error;
    if (recovery) { *deliver=signal; return 1; }
    return 0;
}
int md_svc_machine_seccomp(struct md_svc_registry *r,pid_t pid,struct md_task_signal_stop *stop) {
    md_svc_trace_fn trace=stop->trace;
    if (!r) return 0;
    int error=md_task_signal_info(pid,stop);
    if (error) return error;
    if (stop->info.si_signo!=SIGSYS || stop->info.si_code!=SYS_SECCOMP) return 0;
    uintptr_t pc=(uintptr_t)stop->info.si_call_addr;
    uintptr_t origin=md_svc_registry_call_origin(r,pc);
    if (origin==pc) return 0;
    error=md_task_signal_registers(pid,stop);
    if (error) return error;
    if (stop->regs.pc!=pc) return -EPROTO;
    stop->regs.pc=origin;
    stop->info.si_call_addr=(void *)origin;
    error=regs(trace,pid,PTRACE_SETREGSET,&stop->regs);
    if (!error && trace(PTRACE_SETSIGINFO,pid,NULL,&stop->info)<0) error=-errno;
    return error;
}
int md_svc_machine_signal_committed(md_svc_trace_fn trace,struct md_svc_registry *r,struct md_svc_machine_thread *s,
        pid_t pid,int status) {
    if (!s->signal_alias || !WIFSTOPPED(status)
            || (unsigned)status>>16!=PTRACE_EVENT_STOP || WSTOPSIG(status)!=SIGTRAP) return 0;
    struct user_pt_regs current;
    int error=regs(trace,pid,PTRACE_GETREGSET,&current);
    if (error) return error;
    struct user_pt_regs continuation=current;
    uintptr_t frame=0;
    if (current.sp!=s->signal_sp) {
        frame=current.sp+sizeof(siginfo_t)+offsetof(ucontext_t,uc_mcontext);
        /* sigcontext starts with fault_address, then the architectural GPRs. */
        error=md_svc_registry_read(pid,frame+offsetof(mcontext_t,regs),
            &continuation,sizeof(continuation));
        if (error) return error;
    }
    if (continuation.sp!=s->signal_sp || (continuation.pc!=s->signal_alias
            && continuation.pc!=s->signal_alias+4)) return -EPROTO;
    int restarting=continuation.pc==s->signal_alias;
    continuation.pc=s->signal_svc+(restarting ? 0 : 4);
    if (restarting && (error=retry(r,s,pid,&continuation))) return error;
    if (frame) error=md_svc_registry_write(pid,frame+offsetof(mcontext_t,pc),
        &continuation.pc,sizeof(continuation.pc));
    else error=regs(trace,pid,PTRACE_SETREGSET,&continuation);
    if (!error) {
        s->signal_alias=s->signal_svc=s->signal_sp=0;
        s->draining=0;
    }
    return error;
}
int md_svc_machine_return(struct md_svc_registry *r,struct md_svc_machine_thread *s,
        pid_t pid,struct user_pt_regs *registers) {
    if (!r) return 0;
    int error=0;
    struct md_svc_site *site=md_svc_registry_direct_at(r,pid,registers->pc,registers->sp);
    if (!site) return 0;
    if (registers->pc==site->kernel_alias+4) {
        /* Intercepted cold misses retry at the original SVC. The ordinary
         * syscall owner, not the gate's synthetic PC, then owns execution. */
        registers->pc=site->direct.svc;
        error=retry(r,s,pid,registers);
    } else if (registers->pc==md_svc_direct_checkpoint(&site->direct)+4) {
        int recovered=md_svc_direct_recover(&site->direct,registers,memory,(void *)(intptr_t)pid);
        if (recovered!=1) return recovered<0 ? recovered : -EPROTO;
        if (registers->pc==site->direct.svc) error=retry(r,s,pid,registers);
    } else return 0;
    if (!error) s->draining=0;
    return error ? error : 1;
}
int md_svc_machine_syscall(md_svc_trace_fn trace,struct md_svc_registry *r,struct md_svc_machine_thread *s,pid_t pid,
        const struct user_pt_regs *observed) {
    if (!r) return 0;
    struct user_pt_regs registers=*observed;
    int owned=md_svc_machine_return(r,s,pid,&registers);
    if (owned<=0) return owned;
    return md_svc_machine_redirect(trace,s,pid,&registers);
}
int md_svc_machine_native_entry(const struct md_svc_registry *r,uintptr_t pc,long number) {
    if (!r || r->disabled) return 0;
    uintptr_t origin=md_svc_registry_call_origin(r,pc);
    if (origin==pc) return 0;
    /* An alias retains the caller's arguments and original continuation even
     * for a number not yet admitted by this chain. Its ordinary syscall owner
     * can execute it while discovery independently considers a new gate. */
    int published=0;
    for (unsigned i=0;i<r->count;++i) {
        const struct md_svc_site *site=&r->sites[i];
        if (site->state!=MD_SVC_PUBLISHED || site->direct.svc+4!=origin) continue;
        if (site->direct.number==(unsigned long)number) return 1;
        published=1;
    }
    return published ? 2 : 0;
}
int md_svc_machine_redirect(md_svc_trace_fn trace,struct md_svc_machine_thread *s,pid_t pid,
        const struct user_pt_regs *registers) {
    if (s->syscall_exit) return -EBUSY;
    int number=-1;
    struct iovec io={&number,sizeof(number)};
    if (trace(PTRACE_SETREGSET,pid,(void *)NT_ARM_SYSTEM_CALL,&io)<0) return -errno;
    s->restored=*registers; s->syscall_exit=1;
    return 1;
}
int md_svc_machine_exit(md_svc_trace_fn trace,struct md_svc_machine_thread *s,pid_t pid) {
    if (!s->syscall_exit) return 0;
    int error=regs(trace,pid,PTRACE_SETREGSET,&s->restored);
    if (error) return error;
    s->syscall_exit=0; s->draining=0; return 1;
}
int md_svc_machine_retry(md_svc_trace_fn trace,struct md_svc_machine_thread *s,pid_t pid,struct seccomp_data *observed) {
    struct ptrace_syscall_info info={0};
    if (trace(PTRACE_GET_SYSCALL_INFO,pid,(void *)sizeof(info),&info)<0) return -errno;
    /* A handler may run first or longjmp away. Do not repatch its interrupted
     * call or retain syscall tracing through arbitrary handler execution.
     * A later observed original SVC can republish through normal discovery. */
    int original=info.op==PTRACE_SYSCALL_INFO_ENTRY
        && info.instruction_pointer==s->retry_svc+4 && info.entry.nr==s->retry_number;
    if (original) {
        *observed=(struct seccomp_data){.nr=(int)info.entry.nr,.arch=info.arch,
            .instruction_pointer=info.instruction_pointer};
        memcpy(observed->args,info.entry.args,sizeof(observed->args));
    }
    s->retry_svc=0;
    return original;
}
