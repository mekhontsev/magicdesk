#include "seccomp_program.h"
#include <errno.h>
#include <linux/audit.h>
#include <string.h>

static __attribute__((always_inline)) inline int evaluate(const struct sock_fprog *p,
        const struct seccomp_data *call,uint32_t *result,int constant) {
    if (!p || !p->len || p->len > BPF_MAXINSNS || !p->filter || !call || !result) return -EINVAL;
    uint32_t a = 0, x = 0, memory[BPF_MEMWORDS];
    /* The per-slot initialization mask rejects reads before writes; clearing
     * all scratch words on every admission would do no useful work. */
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
                if (constant && i.k!=offsetof(struct seccomp_data,nr)
                        && i.k!=offsetof(struct seccomp_data,arch)) return -EAGAIN;
                __builtin_memcpy(&value, (const char *)call + i.k, sizeof(value)); break;
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
int md_seccomp_program_eval(const struct sock_fprog *p,const struct seccomp_data *call,uint32_t *result) {
    return evaluate(p,call,result,0);
}
int md_seccomp_program_constant(const struct sock_fprog *p,int number,uint32_t arch,uint32_t *result) {
    struct seccomp_data call={.nr=number,.arch=arch};
    return evaluate(p,&call,result,1);
}
int md_seccomp_cache_allows(const struct md_seccomp_cache *cache,const struct seccomp_data *call) {
    unsigned number=(unsigned)call->nr;
    return call->arch==AUDIT_ARCH_AARCH64 && number<64*MD_SECCOMP_CACHE_WORDS
        && !!(cache->allow[number/64]&(UINT64_C(1)<<(number%64)));
}
void md_seccomp_cache_build(struct md_seccomp_cache *cache,const struct sock_fprog *program,
        const struct md_seccomp_cache *parent) {
    *cache=(struct md_seccomp_cache){0};
    for (unsigned number=0;number<64*MD_SECCOMP_CACHE_WORDS;++number) {
        uint64_t bit=UINT64_C(1)<<(number%64);
        if (parent && !(parent->allow[number/64]&bit)) continue;
        uint32_t result;
        if (!md_seccomp_program_constant(program,(int)number,AUDIT_ARCH_AARCH64,&result)
                && (result&SECCOMP_RET_ACTION_FULL)==SECCOMP_RET_ALLOW) cache->allow[number/64]|=bit;
    }
}

uint32_t md_seccomp_policy_merge(uint32_t older, uint32_t newer) {
    /* Linux compares signed actions; KILL_PROCESS sorts before KILL_THREAD.
     * With equal precedence the most recently installed filter supplies data. */
    return (int32_t)(newer & SECCOMP_RET_ACTION_FULL) <= (int32_t)(older & SECCOMP_RET_ACTION_FULL)
        ? newer : older;
}

int md_seccomp_view_eval(const struct md_seccomp_view *view,const struct seccomp_data *call,uint32_t *result) {
    if (!call || !result) return -EINVAL;
    if (view && md_seccomp_cache_allows(&view->cache,call)) { *result=SECCOMP_RET_ALLOW; return 0; }
    uint32_t decision=SECCOMP_RET_ALLOW;
    for (;view;view=view->parent) {
        struct sock_fprog program={.len=view->count,.filter=(struct sock_filter *)view->code};
        uint32_t next;
        int error=md_seccomp_program_eval(&program,call,&next);
        if (error) return error;
        /* Walk newest first; equal actions keep the newest filter's data. */
        decision=md_seccomp_policy_merge(next,decision);
    }
    *result=decision;
    return 0;
}
