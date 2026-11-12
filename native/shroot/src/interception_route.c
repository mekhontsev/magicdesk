#include "interception_route.h"
#include <string.h>

static void cache(struct md_seccomp_cache *out,unsigned short count,struct sock_filter *code) {
    struct sock_fprog program={count,code};
    md_seccomp_cache_build(out,&program,NULL);
}
int md_interception_routes_init(struct md_interception_routes *r,const struct md_interception_abi *abi) {
    memset(r,0,sizeof(*r));
    md_interception_program_build(abi,&r->base);
    md_signal_program_build(abi,&r->signals);
    md_pid_filter_build(abi,&r->pid);
    cache(&r->base_allow,r->base.count,r->base.code);
    cache(&r->signal_allow,r->signals.count,r->signals.code);
    cache(&r->pid_allow,r->pid.count,r->pid.code);
    struct md_fd_filter_gates gates=md_fd_filter_runtime_gates(abi);
    for (unsigned k=0;k<MD_FD_SELECTOR_KINDS;++k) {
        int error=md_fd_filter_build(k,-1,&gates,&r->fd[k].all);
        if (!error) error=md_fd_filter_build(k,0,&gates,&r->fd[k].one);
        if (error) return error;
        cache(&r->fd[k].allow,r->fd[k].all.count,r->fd[k].all.code);
        /* The generator's FD predicate is AND(mask), JEQ(fd & mask). Record
         * its parameter slots once; evaluation retains the complete program,
         * including global branches, alternate FD arguments and gate checks. */
        const struct md_fd_filter_program *p=&r->fd[k].one;
        for (unsigned i=1;i<p->count;++i)
            if (p->code[i-1].code==(BPF_ALU|BPF_AND|BPF_K) && p->code[i-1].k==MD_FD_SELECTOR_MASK
                    && p->code[i].code==(BPF_JMP|BPF_JEQ|BPF_K)) {
                unsigned slot=r->fd[k].parameters++;
                if (slot>=sizeof(r->fd[k].parameter)/sizeof(*r->fd[k].parameter)) return -E2BIG;
                r->fd[k].parameter[slot]=i;
            }
    }
    return 0;
}
static int evaluate(const struct md_seccomp_cache *cache,unsigned short count,const struct sock_filter *code,
        const struct seccomp_data *call,uint32_t *result) {
    if (md_seccomp_cache_allows(cache,call)) { *result=SECCOMP_RET_ALLOW; return 0; }
    struct sock_fprog program={count,(struct sock_filter *)code};
    return md_seccomp_program_eval(&program,call,result);
}
static int selected(const struct md_interception_routes *r,unsigned kind,const struct md_fd_selector_view *view,
        const struct seccomp_data *call,uint32_t *result) {
    int error=evaluate(&r->fd[kind].allow,r->fd[kind].all.count,r->fd[kind].all.code,call,result);
    if (error || *result==SECCOMP_RET_ALLOW || (view->all&(1u<<kind))) return error;
    unsigned candidates[7],count=0,unused=0;
    for (;;) {
        unsigned i=0;
        while (i<6 && (call->args[i]&MD_FD_SELECTOR_MASK)!=unused) ++i;
        if (i==6) break;
        ++unused;
    }
    /* A nonmatching number evaluates the installed class's global branches,
     * such as dup and writable mprotect, without inventing an FD match. */
    candidates[count++]=unused;
    for (unsigned i=0;i<6;++i) {
        unsigned fd=call->args[i]&MD_FD_SELECTOR_MASK;
        if (!(view->bits[kind][fd/64]&(UINT64_C(1)<<(fd%64)))) continue;
        unsigned j=0;
        while (j<count && candidates[j]!=fd) ++j;
        if (j==count) candidates[count++]=fd;
    }
    struct md_fd_filter_program program=r->fd[kind].one;
    *result=SECCOMP_RET_ALLOW;
    for (unsigned i=0;i<count;++i) {
        for (unsigned j=0;j<r->fd[kind].parameters;++j)
            program.code[r->fd[kind].parameter[j]].k=candidates[i];
        struct sock_fprog p={program.count,program.code}; uint32_t next;
        error=md_seccomp_program_eval(&p,call,&next);
        if (error) return error;
        *result=md_seccomp_policy_merge(*result,next);
    }
    return 0;
}
int md_interception_routes_eval(const struct md_interception_routes *r,const struct md_fd_selector_view *view,
        unsigned flags,const struct seccomp_data *call,uint32_t *result) {
    uint32_t decision,next;
    int error=evaluate(&r->base_allow,r->base.count,r->base.code,call,&decision);
    for (unsigned k=0;!error && k<MD_FD_SELECTOR_KINDS;++k) if (view->active&(1u<<k)) {
        error=selected(r,k,view,call,&next);
        if (!error) decision=md_seccomp_policy_merge(decision,next);
    }
    if (!error && (flags&MD_ROUTE_PID)) {
        error=evaluate(&r->pid_allow,r->pid.count,r->pid.code,call,&next);
        if (!error) decision=md_seccomp_policy_merge(decision,next);
    }
    if (!error && (flags&MD_ROUTE_SIGNALS)) {
        error=evaluate(&r->signal_allow,r->signals.count,r->signals.code,call,&next);
        if (!error) decision=md_seccomp_policy_merge(decision,next);
    }
    if (!error) *result=decision;
    return error;
}
void md_interception_route_publish(struct md_interception_route_view *out,const struct md_fd_selector_view *view,unsigned flags) {
    for (unsigned k=0;k<MD_FD_SELECTOR_KINDS;++k) if (view->active&(1u<<k))
        for (unsigned i=0;i<MD_FD_SELECTOR_WORDS;++i)
            atomic_store_explicit(&out->bits[k][i],view->bits[k][i],memory_order_relaxed);
    atomic_store_explicit(&out->all,view->all,memory_order_release);
    atomic_store_explicit(&out->active,view->active,memory_order_release);
    atomic_store_explicit(&out->flags,flags,memory_order_release);
}
int md_interception_route_eval(const struct md_interception_routes *r,const struct md_interception_route_view *view,
        const struct seccomp_data *call,uint32_t *result) {
    struct md_fd_selector_view snapshot={.active=atomic_load_explicit(&view->active,memory_order_acquire),
        .all=atomic_load_explicit(&view->all,memory_order_acquire)};
    unsigned flags=atomic_load_explicit(&view->flags,memory_order_acquire);
    for (unsigned k=0;k<MD_FD_SELECTOR_KINDS;++k)
        if ((snapshot.active&~snapshot.all)&(1u<<k))
            for (unsigned i=0;i<MD_FD_SELECTOR_WORDS;++i)
                snapshot.bits[k][i]=atomic_load_explicit(&view->bits[k][i],memory_order_relaxed);
    return md_interception_routes_eval(r,&snapshot,flags,call,result);
}
