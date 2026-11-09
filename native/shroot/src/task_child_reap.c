#define _GNU_SOURCE
#include "task_child_reap.h"
#include "task_copy.h"
#include "task_pending.h"
#include <errno.h>
#include <stddef.h>
#include <sys/syscall.h>
#include <sys/wait.h>

enum { REAP=1, DRAIN_PRIVATE, DRAIN_SHARED, RESTORE_SHARED, RESTORE_PRIVATE, COMPLETE };
static int child_notice(void *context,const siginfo_t *info) {
    if (info->si_signo!=SIGCHLD) return 0;
    *(siginfo_t *)context=*info; return 1;
}
static int private_notice(const struct md_task_child_reap *r,const siginfo_t *info) {
    return info->si_signo==SIGCHLD && info->si_pid==r->child
        && (info->si_code==CLD_EXITED || info->si_code==CLD_KILLED || info->si_code==CLD_DUMPED);
}
static int publish(struct md_task_child_reap *r) {
    ssize_t count=md_task_copy_direct(r->call.actor,r->peer,r->scratch,sizeof(*r->scratch),1);
    return count==(ssize_t)sizeof(*r->scratch) ? 0 : count<0 ? (int)count : -EFAULT;
}
static int drain(struct md_task_child_reap *r,int stage) {
    r->stage=stage;
    *r->scratch=(struct md_task_child_reap_scratch){.mask=UINT64_C(1)<<(SIGCHLD-1)};
    int error=publish(r);
    struct md_task_call_arguments args={.number=SYS_rt_sigtimedwait,
        .value={r->peer+offsetof(struct md_task_child_reap_scratch,mask),
            r->peer+offsetof(struct md_task_child_reap_scratch,info),
            r->peer+offsetof(struct md_task_child_reap_scratch,timeout),sizeof(uint64_t)}};
    return error ? error : md_task_call_again(&r->call,&args);
}
static int restore(struct md_task_child_reap *r,int directed,const siginfo_t *info) {
    r->stage=directed ? RESTORE_PRIVATE : RESTORE_SHARED;
    r->scratch->info=*info;
    int error=publish(r);
    uintptr_t peer=r->peer+offsetof(struct md_task_child_reap_scratch,info);
    struct md_task_call_arguments args=directed
        ? (struct md_task_call_arguments){.number=SYS_rt_tgsigqueueinfo,.value={r->group,r->call.actor,SIGCHLD,peer}}
        /* Positive native si_code is admitted only for the calling TID.
         * rt_sigqueueinfo still queues a process-directed signal when its
         * target is that non-leader TID; rt_tgsigqueueinfo selects a thread. */
        : (struct md_task_call_arguments){.number=SYS_rt_sigqueueinfo,.value={r->call.actor,SIGCHLD,peer}};
    return error ? error : md_task_call_again(&r->call,&args);
}
static int finish(struct md_task_child_reap *r) {
    r->stage=COMPLETE; return 1;
}
static int restore_directed(struct md_task_child_reap *r) {
    return r->directed.si_signo ? restore(r,1,&r->directed) : finish(r);
}
int md_task_child_reap_begin(struct md_task_child_reap *r,long (*trace)(int,pid_t,void *,void *),
        pid_t actor,pid_t group,pid_t child,enum md_task_call_boundary boundary,const struct user_pt_regs *regs,
        uintptr_t call,uintptr_t returned,struct md_task_child_reap_scratch *scratch,uintptr_t peer,
        const siginfo_t *displaced) {
    if (!r || !trace || actor<=0 || group<=0 || child<=0 || !regs || !scratch || !peer) return -EINVAL;
    if (r->stage && r->stage!=COMPLETE) return -EBUSY;
    *r=(struct md_task_child_reap){.scratch=scratch,.peer=peer,.group=group,.child=child,
        .stage=REAP,.displaced=displaced};
    struct md_task_call_arguments args={.number=SYS_wait4,.value={child,0,__WALL|WNOHANG,0}};
    return md_task_call_begin(&r->call,trace,actor,boundary,regs,call,returned,&args);
}
int md_task_child_reap_event(struct md_task_child_reap *r,int status) {
    if (!r || !r->stage || r->stage==COMPLETE) return -EINVAL;
    int ready=md_task_call_event(&r->call,status);
    if (ready!=1) return ready;
    long result=r->call.result;
    if (r->stage==REAP) {
        if (result!=r->child && result!=-ECHILD) return result<0 ? (int)result : -EPROTO;
        siginfo_t shared={0};
        int pending=md_task_pending_scan_scope(r->call.trace,r->call.actor,PTRACE_PEEKSIGINFO_SHARED,child_notice,&shared);
        if (pending<0) return pending;
        /* Native coalescing preserved an older real notice: no draining or
         * requeue is needed, and private directed signals remain untouched. */
        if (!private_notice(r,&shared)) return finish(r);
        pending=md_task_pending_scan(r->call.trace,r->call.actor,child_notice,&r->directed);
        return pending<0 ? pending : drain(r,pending ? DRAIN_PRIVATE : DRAIN_SHARED);
    }
    if (r->stage==DRAIN_PRIVATE || r->stage==DRAIN_SHARED) {
        if (result!=SIGCHLD) return result<0 ? (int)result : -EPROTO;
        siginfo_t info;
        ssize_t count=md_task_copy_direct(r->call.actor,r->peer+offsetof(struct md_task_child_reap_scratch,info),
            &info,sizeof(info),0);
        if (count!=sizeof(info)) return count<0 ? (int)count : -EFAULT;
        if (r->stage==DRAIN_PRIVATE) {
            r->directed=info;
            return drain(r,DRAIN_SHARED);
        }
        if (!private_notice(r,&info)) return restore(r,0,&info);
        if (r->displaced && r->displaced->si_signo) return restore(r,0,r->displaced);
        return restore_directed(r);
    }
    if (result) return result<0 ? (int)result : -EPROTO;
    return r->stage==RESTORE_SHARED ? restore_directed(r) : finish(r);
}
