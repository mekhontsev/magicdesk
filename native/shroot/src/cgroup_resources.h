#ifndef MD_CGROUP_RESOURCES_H
#define MD_CGROUP_RESOURCES_H
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/types.h>

enum { MD_CG_PIDS=1, MD_CG_CPU=2, MD_CG_CPUSET=4, MD_CG_MEMORY=8,
    MD_CG_CONTROLLERS=MD_CG_PIDS|MD_CG_CPU|MD_CG_CPUSET|MD_CG_MEMORY };
enum md_cgroup_resource {
    MD_CG_CPU_STAT, MD_CG_CPU_WEIGHT, MD_CG_CPU_MAX, MD_CG_CPU_BURST,
    MD_CG_CPUSET_CPUS, MD_CG_CPUSET_MEMS, MD_CG_CPUSET_CPUS_EFFECTIVE, MD_CG_CPUSET_MEMS_EFFECTIVE,
    MD_CG_MEMORY_CURRENT, MD_CG_MEMORY_PEAK, MD_CG_MEMORY_MIN, MD_CG_MEMORY_LOW,
    MD_CG_MEMORY_HIGH, MD_CG_MEMORY_MAX, MD_CG_MEMORY_SWAP_CURRENT, MD_CG_MEMORY_SWAP_MAX,
    MD_CG_MEMORY_STAT, MD_CG_MEMORY_EVENTS, MD_CG_MEMORY_EVENTS_LOCAL, MD_CG_MEMORY_OOM_GROUP,
    MD_CG_RESOURCE_COUNT
};
struct md_cgroup_control { const char *name; mode_t mode; unsigned views,controller,root; };
extern const struct md_cgroup_control md_cgroup_resource_controls[MD_CG_RESOURCE_COUNT];
struct md_cgroup_policy {
    uint64_t values[MD_CG_RESOURCE_COUNT], period;
    cpu_set_t cpus,mems;
};
struct md_cgroup_usage {
    uint64_t user,system,minor,major,anon,file,shmem,swap,peak;
};
struct md_cgroup_task_usage { uint64_t user,system,minor,major; };
void md_cgroup_policy_init(struct md_cgroup_policy *);
void md_cgroup_policy_reset(struct md_cgroup_policy *,unsigned);
int md_cgroup_policy_write(struct md_cgroup_policy *,unsigned,char *,
    const cpu_set_t *,const cpu_set_t *);
void md_cgroup_resource_text(FILE *,const struct md_cgroup_policy *,unsigned,
    const struct md_cgroup_usage *,const cpu_set_t *,const cpu_set_t *);
int md_cgroup_machine_sets(cpu_set_t *,cpu_set_t *);
int md_cgroup_task_sample(pid_t,pid_t,struct md_cgroup_task_usage *);
int md_cgroup_memory_sample(pid_t,pid_t,struct md_cgroup_usage *);
void md_cgroup_controller_text(FILE *,unsigned);
unsigned md_cgroup_controller(const char *);
#endif
