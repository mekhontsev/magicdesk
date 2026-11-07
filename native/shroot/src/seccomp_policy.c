#include "seccomp_policy.h"
#include "fd_weak.h"
#include <errno.h>
#include <assert.h>
#include <stddef.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>

int md_seccomp_program_eval(const struct sock_fprog *p, const struct seccomp_data *call, uint32_t *result) {
    if (!p || !p->len || p->len > BPF_MAXINSNS || !p->filter || !call || !result) return -EINVAL;
    uint32_t a = 0, x = 0, memory[BPF_MEMWORDS] = {0};
    unsigned initialized = 0;
    for (unsigned pc = 0; pc < p->len; ++pc) {
        struct sock_filter i = p->filter[pc];
        uint32_t operand = BPF_SRC(i.code) == BPF_X ? x : i.k;
        unsigned jump = 0;
        switch (BPF_CLASS(i.code)) {
        case BPF_LD:
        case BPF_LDX: {
            uint32_t value;
            switch (BPF_MODE(i.code)) {
            case BPF_IMM: value = i.k; break;
            case BPF_MEM:
                if (i.k >= BPF_MEMWORDS || !(initialized & (1U << i.k))) return -EINVAL;
                value = memory[i.k]; break;
            case BPF_LEN: value = sizeof(*call); break;
            case BPF_ABS:
                if (i.code != (BPF_LD | BPF_W | BPF_ABS) || i.k % 4 || i.k > sizeof(*call) - 4)
                    return -EINVAL;
                memcpy(&value, (const char *)call + i.k, sizeof(value)); break;
            default: return -EOPNOTSUPP;
            }
            if (BPF_CLASS(i.code) == BPF_LD) a = value; else x = value;
            break;
        }
        case BPF_ST:
        case BPF_STX:
            if (i.k >= BPF_MEMWORDS) return -EINVAL;
            memory[i.k] = BPF_CLASS(i.code) == BPF_ST ? a : x;
            initialized |= 1U << i.k; break;
        case BPF_ALU:
            switch (BPF_OP(i.code)) {
            case BPF_ADD: a += operand; break;
            case BPF_SUB: a -= operand; break;
            case BPF_MUL: a *= operand; break;
            case BPF_DIV:
                if (!operand) { *result = 0; return 0; }
                a /= operand; break;
            case BPF_OR: a |= operand; break;
            case BPF_AND: a &= operand; break;
            case BPF_XOR: a ^= operand; break;
            case BPF_LSH: a <<= operand & 31; break;
            case BPF_RSH: a >>= operand & 31; break;
            case BPF_NEG: a = 0U - a; break;
            default: return -EOPNOTSUPP;
            }
            break;
        case BPF_JMP: {
            int yes;
            switch (BPF_OP(i.code)) {
            case BPF_JA: jump = i.k; goto branch;
            case BPF_JEQ: yes = a == operand; break;
            case BPF_JGT: yes = a > operand; break;
            case BPF_JGE: yes = a >= operand; break;
            case BPF_JSET: yes = !!(a & operand); break;
            default: return -EOPNOTSUPP;
            }
            jump = yes ? i.jt : i.jf;
        branch:
            if (jump >= (unsigned)p->len - pc - 1) return -EINVAL;
            pc += jump; break;
        }
        case BPF_RET:
            if (i.code != (BPF_RET | BPF_K) && i.code != (BPF_RET | BPF_A)) return -EINVAL;
            *result = BPF_RVAL(i.code) == BPF_A ? a : i.k; return 0;
        case BPF_MISC:
            if (i.code == (BPF_MISC | BPF_TAX)) x = a;
            else if (i.code == (BPF_MISC | BPF_TXA)) a = x;
            else return -EOPNOTSUPP;
            break;
        default: return -EOPNOTSUPP;
        }
    }
    return -EINVAL;
}

uint32_t md_seccomp_policy_merge(uint32_t older, uint32_t newer) {
    /* Linux compares signed actions; KILL_PROCESS sorts before KILL_THREAD.
     * With equal precedence the most recently installed filter supplies data. */
    return (int32_t)(newer & SECCOMP_RET_ACTION_FULL) <= (int32_t)(older & SECCOMP_RET_ACTION_FULL)
        ? newer : older;
}

struct md_seccomp_policy {
    struct md_seccomp_policy *parent;
    struct md_fd_weak *listener;
    atomic_size_t references;
    size_t users;
    void (*activity)(struct md_fd_object *,int);
    unsigned short count;
    unsigned char may_trace,may_notify;
    struct sock_filter code[];
};

struct md_seccomp_policy *md_seccomp_policy_retain(struct md_seccomp_policy *p) {
    if (p) atomic_fetch_add_explicit(&p->references,1,memory_order_relaxed);
    return p;
}
void md_seccomp_policy_release(struct md_seccomp_policy *p) {
    while (p && atomic_fetch_sub_explicit(&p->references,1,memory_order_acq_rel)==1) {
        assert(!p->users);
        struct md_seccomp_policy *parent = p->parent;
        md_fd_weak_release(p->listener);
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
    struct md_seccomp_policy *p = malloc(sizeof(*p) + source->len * sizeof(*p->code));
    if (!p) return -ENOMEM;
    *p = (struct md_seccomp_policy){.parent = md_seccomp_policy_retain(parent), .references = 1, .count = source->len};
    memcpy(p->code, source->filter, p->count * sizeof(*p->code));
    p->may_trace=parent && parent->may_trace;
    p->may_notify=parent && parent->may_notify;
    for (unsigned i=0;i<p->count && (!p->may_trace || !p->may_notify);++i) {
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
int md_seccomp_policy_decide(const struct md_seccomp_policy *p, const struct seccomp_data *call,
        uint32_t *result, const struct md_seccomp_policy **winner) {
    if (!call || !result) return -EINVAL;
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
    if (notify || (route && route->owned_admission)) {
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

int md_seccomp_policy_route(const struct md_seccomp_policy *p,const uint32_t *calls,size_t count,
        uint16_t cookie,struct sock_filter *out,size_t capacity,const struct md_seccomp_route *route) {
    if (!route || !route->owned_admission) return policy_route(p,calls,count,cookie,out,capacity,route);
    if (!out || !route->delegation_pc || route->instruction_pointer) return -EINVAL;
    struct sock_filter *code=calloc(BPF_MAXINSNS,sizeof(*code));
    if (!code) return -ENOMEM;
    int length=policy_route(p,calls,count,cookie,code,BPF_MAXINSNS,route);
    if (length<0) { free(code); return length; }
    unsigned used=(unsigned)length;
    for (unsigned i=0;i<(unsigned)length;++i) {
        struct sock_filter ret=code[i];
        if (BPF_CLASS(ret.code)!=BPF_RET || (ret.code==(BPF_RET|BPF_K)
                && ((ret.k&SECCOMP_RET_ACTION_FULL)==SECCOMP_RET_ALLOW
                    || ret.k==(SECCOMP_RET_TRACE|cookie)))) continue;
        unsigned target=used;
        /* Share identical exits without expanding the original branch graph. */
        for (unsigned n=0;n<i;++n) if (code[n].code==(BPF_JMP|BPF_JA)) {
            unsigned at=n+1+code[n].k;
            if (at<(unsigned)length || at>=used) continue;
            unsigned size=(ret.code==(BPF_RET|BPF_A) ? 8 : 6)
                +(route->admission_pc ? 7 : 0)+(route->substitution_pc ? 7 : 0);
            if (at+size<=used && code[at+size-1].code==ret.code && code[at+size-1].k==ret.k) {
                target=at; break;
            }
        }
        if (target==used) {
            int dynamic=ret.code==(BPF_RET|BPF_A);
            unsigned size=(dynamic ? 8 : 6)+(route->admission_pc ? 7 : 0)
                +(route->substitution_pc ? 7 : 0);
            if (used+size>BPF_MAXINSNS) { free(code); return -E2BIG; }
            if (dynamic) code[used++]=(struct sock_filter)BPF_STMT(BPF_MISC|BPF_TAX,0);
            if (route->substitution_pc) {
                uint64_t pc=route->substitution_pc;
                const struct sock_filter substitution[]={
                    BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
                    BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,route->substitution_number,0,5),
                    BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4),
                    BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)(pc>>32),0,3),
                    BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
                    BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)pc,0,1),
                    BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|cookie)};
                memcpy(code+used,substitution,sizeof(substitution)); used+=sizeof(substitution)/sizeof(*substitution);
            }
            if (route->admission_pc) {
                uint64_t pc=route->admission_pc;
                const struct sock_filter admission[]={
                    BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
                    BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_gettid,0,5),
                    BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4),
                    BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)(pc>>32),0,3),
                    BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
                    BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)pc,0,1),
                    BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|cookie)};
                memcpy(code+used,admission,sizeof(admission)); used+=sizeof(admission)/sizeof(*admission);
            }
            uint64_t pc=route->delegation_pc;
            const struct sock_filter gate[]={
                BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4),
                BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)(pc>>32),0,3),
                BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
                BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)pc,0,1),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|cookie)};
            memcpy(code+used,gate,sizeof(gate)); used+=sizeof(gate)/sizeof(*gate);
            if (dynamic) code[used++]=(struct sock_filter)BPF_STMT(BPF_MISC|BPF_TXA,0);
            code[used++]=ret;
        }
        code[i]=(struct sock_filter)BPF_STMT(BPF_JMP|BPF_JA,target-i-1);
    }
    if (used>capacity) { free(code); return -ENOSPC; }
    memcpy(out,code,used*sizeof(*code)); free(code); return (int)used;
}
