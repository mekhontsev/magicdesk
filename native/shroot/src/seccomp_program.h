#ifndef MD_SECCOMP_PROGRAM_H
#define MD_SECCOMP_PROGRAM_H
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdint.h>

/* Pure evaluation of a kernel-validated snapshot. No host objects, allocation,
 * syscalls or mutable state: shared by the coordinator and the guest entry. */
int md_seccomp_program_eval(const struct sock_fprog *,const struct seccomp_data *,uint32_t *);
/* Prove a result using only nr/arch. Unknown arguments or IP return EAGAIN;
 * this is a cacheability proof, not a filter verifier or an admission result. */
int md_seccomp_program_constant(const struct sock_fprog *,int number,uint32_t arch,uint32_t *);
enum { MD_SECCOMP_CACHE_WORDS=8 };
struct md_seccomp_cache { uint64_t allow[MD_SECCOMP_CACHE_WORDS]; };
void md_seccomp_cache_build(struct md_seccomp_cache *,const struct sock_fprog *,const struct md_seccomp_cache *parent);
int md_seccomp_cache_allows(const struct md_seccomp_cache *,const struct seccomp_data *);
uint32_t md_seccomp_policy_merge(uint32_t older,uint32_t newer);
/* Read-only peer representation. Parents may live in an older retained mapping;
 * no listener or coordinator object is exposed. The publisher owns lifetime. */
struct md_seccomp_view {
    const struct md_seccomp_view *parent;
    uint16_t count;
    uint16_t reserved[3];
    struct md_seccomp_cache cache;
    struct sock_filter code[];
};
int md_seccomp_view_eval(const struct md_seccomp_view *,const struct seccomp_data *,uint32_t *);
static inline long md_seccomp_policy_error(uint32_t decision) {
    unsigned error=decision&SECCOMP_RET_DATA;
    return -(long)(error>4095 ? 4095 : error);
}
#endif
