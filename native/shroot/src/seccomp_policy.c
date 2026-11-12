#include "seccomp_policy.h"
#include "fd_weak.h"
#include <errno.h>
#include <linux/audit.h>
#include <assert.h>
#include <stddef.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>

struct md_seccomp_policy {
    struct md_seccomp_policy *parent;
    uint64_t identity,entry_image;
    struct md_seccomp_gate *internal_gates;
    size_t internal_gate_count;
    struct md_seccomp_cache cache;
    struct md_fd_weak *listener;
    atomic_size_t references;
    size_t users;
    void (*activity)(struct md_fd_object *,int);
    unsigned short count;
    unsigned char may_trace,may_notify,uses_ip,entry_portable;
    struct sock_filter code[];
};

static uint64_t next_identity;
uint64_t md_seccomp_policy_identity(const struct md_seccomp_policy *p) { return p ? p->identity : 0; }
const struct md_seccomp_policy *md_seccomp_policy_parent(const struct md_seccomp_policy *p) {
    return p ? p->parent : NULL;
}
static size_t view_size(const struct md_seccomp_policy *p) {
    return sizeof(struct md_seccomp_view)+p->count*sizeof(*p->code);
}
int md_seccomp_policy_view_size(const struct md_seccomp_policy *p,uint64_t ancestor,size_t *out) {
    if (!out) return -EINVAL;
    size_t size=0;
    for (;p && p->identity!=ancestor;p=p->parent) {
        size_t bytes=view_size(p);
        if (bytes>SIZE_MAX-size) return -EOVERFLOW;
        size+=bytes;
    }
    if (ancestor && !p) return -ENOENT;
    *out=size;
    return 0;
}
int md_seccomp_policy_view_write(const struct md_seccomp_policy *p,uint64_t ancestor,
        void *out,size_t capacity,uintptr_t peer,uintptr_t parent) {
    size_t bytes;
    int error=md_seccomp_policy_view_size(p,ancestor,&bytes);
    if (error) return error;
    if (capacity<bytes) return -ENOSPC;
    if ((bytes && (!out || !peer)) || peer>UINTPTR_MAX-bytes
            || (uintptr_t)out%_Alignof(struct md_seccomp_view)
            || peer%_Alignof(struct md_seccomp_view) || parent%_Alignof(struct md_seccomp_view)
            || (!!ancestor!=!!parent)) return -EINVAL;
    size_t offset=0;
    for (;p && p->identity!=ancestor;p=p->parent) {
        struct md_seccomp_view *view=(void *)((char *)out+offset);
        offset+=view_size(p);
        *view=(struct md_seccomp_view){.count=p->count,.cache=p->cache,
            .parent=(const void *)(offset<bytes ? peer+offset : parent)};
        memcpy(view->code,p->code,p->count*sizeof(*p->code));
    }
    return 0;
}

struct md_seccomp_policy *md_seccomp_policy_retain(struct md_seccomp_policy *p) {
    if (p) atomic_fetch_add_explicit(&p->references,1,memory_order_relaxed);
    return p;
}
void md_seccomp_policy_release(struct md_seccomp_policy *p) {
    while (p && atomic_fetch_sub_explicit(&p->references,1,memory_order_acq_rel)==1) {
        assert(!p->users);
        struct md_seccomp_policy *parent = p->parent;
        md_fd_weak_release(p->listener);
        free(p->internal_gates);
        free(p); p = parent;
    }
}
static void activity(struct md_seccomp_policy *p,int active) {
    if (!p->activity) return;
    struct md_fd_object *object=md_fd_weak_lock(p->listener);
    if (object) { p->activity(object,active); md_fd_object_release(object); }
}
void md_seccomp_policy_replace(struct md_seccomp_policy **slot,struct md_seccomp_policy *next) {
    assert(slot);
    struct md_seccomp_policy *previous=*slot;
    if (previous==next) return;
    md_seccomp_policy_retain(next);
    for (struct md_seccomp_policy *p=next;p;p=p->parent) {
        if (p->users++) break;
        activity(p,1);
    }
    *slot=next;
    for (struct md_seccomp_policy *p=previous;p;p=p->parent) {
        assert(p->users);
        if (--p->users) break;
        activity(p,0);
    }
    md_seccomp_policy_release(previous);
}
int md_seccomp_policy_capture(struct md_seccomp_policy *parent, const struct sock_fprog *source,
        struct md_seccomp_policy **out) {
    if (!source || !source->filter || !source->len || source->len > BPF_MAXINSNS || !out) return -EINVAL;
    if (next_identity==UINT64_MAX) return -EOVERFLOW;
    struct md_seccomp_policy *p = malloc(sizeof(*p) + source->len * sizeof(*p->code));
    if (!p) return -ENOMEM;
    *p = (struct md_seccomp_policy){.parent = md_seccomp_policy_retain(parent),
        .identity=++next_identity, .references = 1, .count = source->len};
    memcpy(p->code, source->filter, p->count * sizeof(*p->code));
    struct sock_fprog snapshot={p->count,p->code};
    md_seccomp_cache_build(&p->cache,&snapshot,parent ? &parent->cache : NULL);
    p->may_trace=parent && parent->may_trace;
    p->may_notify=parent && parent->may_notify;
    for (unsigned i=0;i<p->count;++i) {
        if (p->code[i].code==(BPF_LD|BPF_W|BPF_ABS)
                && (p->code[i].k==offsetof(struct seccomp_data,instruction_pointer)
                    || p->code[i].k==offsetof(struct seccomp_data,instruction_pointer)+4)) p->uses_ip=1;
        if (p->code[i].code==(BPF_RET|BPF_A)) p->may_trace=p->may_notify=1;
        else if (p->code[i].code==(BPF_RET|BPF_K)) {
            uint32_t action=p->code[i].k&SECCOMP_RET_ACTION_FULL;
            p->may_trace|=action==SECCOMP_RET_TRACE;
            p->may_notify|=action==SECCOMP_RET_USER_NOTIF;
        }
    }
    *out = p;
    return 0;
}
int md_seccomp_policy_may_trace(const struct md_seccomp_policy *p) { return p && p->may_trace; }
int md_seccomp_policy_may_notify(const struct md_seccomp_policy *p) { return p && p->may_notify; }
int md_seccomp_policy_address_independent(const struct md_seccomp_policy *p) {
    for (;p;p=p->parent) if (p->uses_ip) return 0;
    return 1;
}
uint64_t md_seccomp_policy_entry_image(const struct md_seccomp_policy *p) { return p ? p->entry_image : 0; }
int md_seccomp_policy_internal_gates(const struct md_seccomp_policy *p,
        const struct md_seccomp_gate *gates,size_t count) {
    if (!p || !gates || !count || p->internal_gate_count!=count) return 0;
    for (size_t i=0;i<count;++i) {
        const struct md_seccomp_gate *a=p->internal_gates+i,*b=gates+i;
        if (a->first!=b->first || a->end!=b->end || a->number!=b->number
                || a->decision!=b->decision) return 0;
    }
    return 1;
}
int md_seccomp_policy_bind_entry(struct md_seccomp_policy *p,uint64_t image,
        const struct md_seccomp_gate *gates,size_t count) {
    assert(p && !p->users && !p->entry_image && !p->internal_gate_count);
    if (!image || (count && !gates) || count>SIZE_MAX/sizeof(*gates)) return -EINVAL;
    if (count && (!p->parent || md_seccomp_policy_internal_gates(p->parent,gates,count))) {
        p->internal_gates=malloc(count*sizeof(*gates));
        if (!p->internal_gates) return -ENOMEM;
        memcpy(p->internal_gates,gates,count*sizeof(*gates));
        p->internal_gate_count=count;
    }
    if (!p->parent || md_seccomp_policy_entry_compatible(p->parent,image)) p->entry_image=image;
    p->entry_portable=p->entry_image && !p->uses_ip && (!p->parent || p->parent->entry_portable);
    return 0;
}
int md_seccomp_policy_entry_checkpoint(const struct md_seccomp_policy *p) {
    if (!p || !p->entry_portable) return -1;
    static const int candidates[]={SYS_gettid,SYS_getpid,SYS_getuid,SYS_geteuid,SYS_getgid,SYS_getegid,SYS_getppid};
    for (unsigned i=0;i<sizeof(candidates)/sizeof(*candidates);++i) {
        struct seccomp_data call={.nr=candidates[i],.arch=AUDIT_ARCH_AARCH64};
        if (md_seccomp_cache_allows(&p->cache,&call)) return candidates[i];
    }
    return -1;
}
int md_seccomp_policy_entry_compatible(const struct md_seccomp_policy *p,uint64_t image) {
    return p && image && (p->entry_image==image || md_seccomp_policy_entry_checkpoint(p)>=0);
}
int md_seccomp_policy_decide(const struct md_seccomp_policy *p, const struct seccomp_data *call,
        uint32_t *result, const struct md_seccomp_policy **winner) {
    if (!call || !result) return -EINVAL;
    if (p && md_seccomp_cache_allows(&p->cache,call)) {
        *result=SECCOMP_RET_ALLOW;
        if (winner) *winner=NULL;
        return 0;
    }
    uint32_t decision = SECCOMP_RET_ALLOW;
    const struct md_seccomp_policy *match = NULL;
    for (; p; p = p->parent) {
        struct sock_fprog program = {.len = p->count, .filter = (struct sock_filter *)p->code};
        uint32_t next;
        int error = md_seccomp_program_eval(&program, call, &next);
        if (error) return error;
        if ((int32_t)(next & SECCOMP_RET_ACTION_FULL) < (int32_t)(decision & SECCOMP_RET_ACTION_FULL)) {
            decision = next; match = p;
        }
    }
    *result = decision;
    if (winner) *winner = match;
    return 0;
}
int md_seccomp_policy_eval(const struct md_seccomp_policy *p, const struct seccomp_data *call, uint32_t *result) {
    return md_seccomp_policy_decide(p, call, result, NULL);
}
int md_seccomp_policy_bind_listener(struct md_seccomp_policy *p,struct md_fd_object *object,
        void (*changed)(struct md_fd_object *,int)) {
    if (!p || !object) return -EINVAL;
    if (p->listener) return -EBUSY;
    for (const struct md_seccomp_policy *parent=p->parent;parent;parent=parent->parent)
        if (!md_fd_weak_expired(parent->listener)) return -EBUSY;
    struct md_fd_weak *weak=md_fd_weak_observe(object);
    if (!weak) return -errno;
    p->listener=weak; p->activity=changed;
    if (changed) changed(object,!!p->users);
    return 0;
}
struct md_fd_object *md_seccomp_policy_listener(const struct md_seccomp_policy *p) {
    return p ? md_fd_weak_lock(p->listener) : NULL;
}
static int policy_route(const struct md_seccomp_policy *p, const uint32_t *calls, size_t count,
        uint16_t cookie, struct sock_filter *out, size_t capacity,
        const struct md_seccomp_route *route) {
    if (!p || (count && !calls) || !out) return -EINVAL;
    const struct md_seccomp_route *notification=route && route->instruction_pointer ? route : NULL;
    uint64_t pc=route ? route->delegation_pc : 0;
    unsigned notify=route ? route->notify_cookie : 0;
    size_t tail=0;
    if (notify || (route && route->gate_count)) {
        /* Appending an epilogue must not make a formerly out-of-range jump or
         * fallthrough valid. Leave all other validation to the native verifier. */
        if (BPF_CLASS(p->code[p->count-1].code)!=BPF_RET) return -EINVAL;
        for (unsigned i=0;i<p->count;++i) {
            const struct sock_filter *at=p->code+i;
            if (BPF_CLASS(at->code)==BPF_JMP) {
                unsigned remaining=p->count-i-1;
                if (BPF_OP(at->code)==BPF_JA ? at->k>=remaining
                        : at->jt>=remaining || at->jf>=remaining) return -EINVAL;
            }
            if (notify && at->code==(BPF_RET|BPF_A)) tail=6;
        }
    }
    if (!count && !notification && !notify) {
        if (capacity<p->count) return -ENOSPC;
        memcpy(out,p->code,p->count*sizeof(*p->code));
        return (int)p->count;
    }
    if (count > (BPF_MAXINSNS - 2U) / 2U) return -E2BIG;
    size_t carrier = notification ? 13 : 0;
    size_t prefix = count || notification ? 2 + count * 2 + carrier + (pc ? 6 : 0) : 0;
    if (prefix + p->count + tail > BPF_MAXINSNS) return -E2BIG;
    if (prefix + p->count + tail > capacity) return -ENOSPC;
    if (notification) {
        uint64_t token = (uintptr_t)p, pc = notification->instruction_pointer;
        const struct sock_filter header[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, notification->number, 0, 10),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)pc, 0, 8),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer) + 4),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, pc >> 32, 0, 6),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[5])),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)token, 0, 3),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[5]) + 4),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, token >> 32, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_ALU | BPF_AND | BPF_K, 0),
        };
        memcpy(out, header, sizeof(header));
    }
    if (prefix) out[carrier] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr));
    for (size_t i = 0; i < count; ++i) {
        out[carrier + 1 + i * 2] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, calls[i], 0, 1);
        out[carrier + 2 + i * 2] = pc
            ? (struct sock_filter)BPF_STMT(BPF_JMP | BPF_JA,2*(count-i)-1)
            : (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | cookie);
    }
    if (pc && prefix) {
        /* Ordinary IO skips IP loads, preserving the kernel's constant-ALLOW
         * cache. Only the selected control numbers inspect the owned gate. */
        const struct sock_filter scoped[]={
            BPF_STMT(BPF_JMP|BPF_JA,5),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)(pc>>32),0,3),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)pc,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|cookie)};
        memcpy(out+carrier+1+2*count,scoped,sizeof(scoped));
    }
    /* Restore the original entry accumulator without defeating the kernel's
     * constant-ALLOW cache for ordinary IO. X and scratch remain untouched. */
    if (prefix) out[prefix - 1] = (struct sock_filter)BPF_STMT(BPF_ALU | BPF_AND | BPF_K, 0);
    memcpy(out + prefix, p->code, p->count * sizeof(*p->code));
    if (notify) {
        for (unsigned i=0;i<p->count;++i) {
            struct sock_filter *at=out+prefix+i;
            if (at->code==(BPF_RET|BPF_K) && (at->k&SECCOMP_RET_ACTION_FULL)==SECCOMP_RET_USER_NOTIF)
                at->k=SECCOMP_RET_TRACE|notify;
            else if (at->code==(BPF_RET|BPF_A))
                *at=(struct sock_filter)BPF_STMT(BPF_JMP|BPF_JA,p->count-i-1);
        }
        if (tail) {
            /* One tail for all dynamic returns, without moving any original
             * branch target or borrowing the program's scratch registers. */
            const struct sock_filter dynamic[]={
                BPF_STMT(BPF_MISC|BPF_TAX,0),
                BPF_STMT(BPF_ALU|BPF_AND|BPF_K,SECCOMP_RET_ACTION_FULL),
                BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SECCOMP_RET_USER_NOTIF,2,0),
                BPF_STMT(BPF_MISC|BPF_TXA,0), BPF_STMT(BPF_RET|BPF_A,0),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|notify)};
            memcpy(out+prefix+p->count,dynamic,sizeof(dynamic));
        }
    }
    return (int)(prefix + p->count + tail);
}

static unsigned gate_size(const struct md_seccomp_gate *gate) {
    return (gate->number>=0 ? 2 : 0)+(gate->first==gate->end ? 5 : 11);
}
static unsigned gate_write(struct sock_filter *code,const struct md_seccomp_gate *gate) {
    unsigned size=gate_size(gate),used=0;
    if (gate->number>=0) {
        code[used++]=(struct sock_filter)BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr));
        code[used++]=(struct sock_filter)BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)gate->number,0,size-2);
    }
    const unsigned ip=offsetof(struct seccomp_data,instruction_pointer);
    if (gate->first==gate->end) {
        const struct sock_filter match[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,ip+4),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)(gate->first>>32),0,3),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,ip),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)gate->first,0,1),
            BPF_STMT(BPF_RET|BPF_K,gate->decision)};
        memcpy(code+used,match,sizeof(match));
    } else {
        /* Lexicographic unsigned halves also handle ranges crossing 4 GiB.
         * Keep X intact: dynamic original returns retain their result there. */
        const struct sock_filter match[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,ip+4),
            BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K,(uint32_t)(gate->first>>32),3,0),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)(gate->first>>32),0,8),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,ip),
            BPF_JUMP(BPF_JMP|BPF_JGE|BPF_K,(uint32_t)gate->first,0,6),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,ip+4),
            BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K,(uint32_t)(gate->end>>32),4,0),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)(gate->end>>32),0,2),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,ip),
            BPF_JUMP(BPF_JMP|BPF_JGE|BPF_K,(uint32_t)gate->end,1,0),
            BPF_STMT(BPF_RET|BPF_K,gate->decision)};
        memcpy(code+used,match,sizeof(match));
    }
    return size;
}
static unsigned constant_allow_prefix(struct sock_filter *out,const struct md_seccomp_cache *cache) {
    unsigned used=4;
    for (unsigned first=0;first<64*MD_SECCOMP_CACHE_WORDS;++first) {
        if (!(cache->allow[first/64]&(UINT64_C(1)<<(first%64)))) continue;
        unsigned last=first;
        while (last+1<64*MD_SECCOMP_CACHE_WORDS
                && (cache->allow[(last+1)/64]&(UINT64_C(1)<<((last+1)%64)))) ++last;
        if (out) {
            if (first==last) out[used]=(struct sock_filter)BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,first,0,1);
            else {
                out[used]=(struct sock_filter)BPF_JUMP(BPF_JMP|BPF_JGE|BPF_K,first,0,2);
                out[used+1]=(struct sock_filter)BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K,last,1,0);
            }
        }
        used+=first==last ? 1 : 2;
        if (out) out[used]=(struct sock_filter)BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW);
        ++used; first=last;
    }
    if (used==4) return 0;
    if (out) {
        out[0]=(struct sock_filter)BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,arch));
        out[1]=(struct sock_filter)BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,AUDIT_ARCH_AARCH64,1,0);
        out[2]=(struct sock_filter)BPF_STMT(BPF_JMP|BPF_JA,used-3);
        out[3]=(struct sock_filter)BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr));
    }
    return used;
}
int md_seccomp_policy_route(const struct md_seccomp_policy *p,const uint32_t *calls,size_t count,
        uint16_t cookie,struct sock_filter *out,size_t capacity,const struct md_seccomp_route *route) {
    if (route && (route->internal_gate_count>route->gate_count
            || (route->internal_gate_count && (!route->entry_first || !route->entry_image)))) return -EINVAL;
    if (!route || !route->gate_count) return policy_route(p,calls,count,cookie,out,capacity,route);
    if (!out || !route->gates || route->instruction_pointer) return -EINVAL;
    unsigned gates=0;
    for (size_t i=0;i<route->gate_count;++i) {
        const struct md_seccomp_gate *gate=route->gates+i;
        unsigned action=gate->decision&SECCOMP_RET_ACTION_FULL;
        if (!gate->first || gate->end<gate->first || gate->number>UINT32_MAX
                || (action!=SECCOMP_RET_ALLOW && action!=SECCOMP_RET_TRACE)) return -EINVAL;
        unsigned size=gate_size(gate);
        if (size>BPF_MAXINSNS-gates) return -E2BIG;
        gates+=size;
    }
    struct sock_filter *code=calloc(BPF_MAXINSNS,sizeof(*code));
    if (!code) return -ENOMEM;
    int length=policy_route(p,calls,count,cookie,code,BPF_MAXINSNS,route);
    if (length<0) { free(code); return length; }
    unsigned used=(unsigned)length;
    if (route->entry_first) {
        /* Constant ALLOW needs neither guest evaluation nor an IP load. All
         * other service calls inspect the owned gate before any guest code,
         * including implicit exits such as division by zero. */
        struct sock_fprog program={used,code};
        struct md_seccomp_cache cache;
        md_seccomp_cache_build(&cache,&program,NULL);
        unsigned prefix=constant_allow_prefix(NULL,&cache)+gates+1;
        int error=prefix>BPF_MAXINSNS-used ? -E2BIG : used+prefix>capacity ? -ENOSPC : 0;
        if (error) { free(code); return error; }
        unsigned at=constant_allow_prefix(out,&cache);
        for (size_t n=0;n<route->gate_count;++n) at+=gate_write(out+at,route->gates+n);
        out[at++]=(struct sock_filter)BPF_STMT(BPF_ALU|BPF_AND|BPF_K,0);
        memcpy(out+at,code,used*sizeof(*code)); free(code);
        return (int)(at+used);
    }
    for (unsigned i=0;i<(unsigned)length;++i) {
        struct sock_filter ret=code[i];
        if (BPF_CLASS(ret.code)!=BPF_RET || (ret.code==(BPF_RET|BPF_K)
                && ((ret.k&SECCOMP_RET_ACTION_FULL)==SECCOMP_RET_ALLOW
                    || ret.k==(SECCOMP_RET_TRACE|cookie)))) continue;
        unsigned target=used,size=gates+1+(ret.code==(BPF_RET|BPF_A) ? 2 : 0);
        /* Share identical exits without expanding the original branch graph. */
        for (unsigned n=0;n<i;++n) if (code[n].code==(BPF_JMP|BPF_JA)) {
            unsigned at=n+1+code[n].k;
            if (at<(unsigned)length || at>=used) continue;
            if (at+size<=used && code[at+size-1].code==ret.code && code[at+size-1].k==ret.k) {
                target=at; break;
            }
        }
        if (target==used) {
            if (size>BPF_MAXINSNS-used) { free(code); return -E2BIG; }
            if (ret.code==(BPF_RET|BPF_A)) code[used++]=(struct sock_filter)BPF_STMT(BPF_MISC|BPF_TAX,0);
            for (size_t n=0;n<route->gate_count;++n) used+=gate_write(code+used,route->gates+n);
            if (ret.code==(BPF_RET|BPF_A)) code[used++]=(struct sock_filter)BPF_STMT(BPF_MISC|BPF_TXA,0);
            code[used++]=ret;
        }
        code[i]=(struct sock_filter)BPF_STMT(BPF_JMP|BPF_JA,target-i-1);
    }
    if (used>capacity) { free(code); return -ENOSPC; }
    memcpy(out,code,used*sizeof(*code)); free(code); return (int)used;
}
