#ifndef MD_KERNEL_POLICY_H
#define MD_KERNEL_POLICY_H
#include "control_file.h"

/* Launch-local configuration, never Android's global sysctls. Panic and key
 * quotas are retained policy only: guests have no kernel panic or keyring API. */
static const struct md_kernel_policy_spec {
    const char *path;
    unsigned initial,maximum;
} md_kernel_policy_specs[]={
    {"sys/vm/overcommit_memory",1,2},
    {"sys/vm/panic_on_oom",0,2},
    {"sys/kernel/panic",0,2147483647U},
    {"sys/kernel/panic_on_oops",0,1},
    {"sys/kernel/keys/root_maxkeys",1000000,2147483647U},
    {"sys/kernel/keys/root_maxbytes",25000000,2147483647U}
};
#define MD_KERNEL_POLICY_COUNT (sizeof(md_kernel_policy_specs)/sizeof(*md_kernel_policy_specs))
struct md_kernel_policy;
struct md_kernel_policy *md_kernel_policy_new(void);
struct md_kernel_policy *md_kernel_policy_retain(struct md_kernel_policy *);
void md_kernel_policy_release(struct md_kernel_policy *);
struct md_control_file md_kernel_policy_file(struct md_kernel_policy *,unsigned,struct md_fs_anchor *);
#endif
