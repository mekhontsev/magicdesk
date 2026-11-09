#include "proc_static.h"
#include "user_namespace.h"
#include "network_namespace.h"
#include "fs_mounts.h"
#include "cgroup.h"
#include <errno.h>
#include <inttypes.h>
#include <linux/magic.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

/* Guest driver families, not Android's hardware inventory. Registration does
 * not grant access to a device: each open still uses its existing provider. */
static const char devices[]=
    "Character devices:\n"
    "  1 mem\n"
    "  5 /dev/tty\n"
    "  5 /dev/ptmx\n"
    " 10 misc\n"
    " 13 input\n"
    "136 pts\n"
    "226 drm\n"
    "\nBlock devices:\n";
static const char filesystems[]=
    "nodev\ttmpfs\nnodev\tramfs\nnodev\tproc\nnodev\tsysfs\nnodev\tdevpts\n"
    "nodev\tcgroup\nnodev\tcgroup2\nnodev\tmqueue\nnodev\tbinfmt_misc\n"
    "nodev\toverlay\nnodev\tfuse\n";
/* No guest owns or activates Android swap devices. Host swapping can still
 * affect native allocations; this is not a claim that the phone has no swap. */
static const char swaps[]="Filename\t\t\t\tType\t\tSize\t\tUsed\t\tPriority\n";
/* Compatibility providers in the selected managed network, not loadable
 * Android modules. Zero sizes/addresses avoid inventing host allocations. */
static const char network_modules[]=
    "nf_tables 0 0 - Live 0x0000000000000000\n"
    "nf_conntrack 0 0 - Live 0x0000000000000000\n"
    "ip_set 0 0 - Live 0x0000000000000000\n"
    "br_netfilter 0 0 - Live 0x0000000000000000\n";
static const struct static_entry {
    enum md_proc_kind kind;
    const char *name,*text;
    size_t size;
} entries[]={
    {MD_PROC_DEVICES,"devices",devices,sizeof(devices)-1},
    {MD_PROC_FILESYSTEMS,"filesystems",filesystems,sizeof(filesystems)-1},
    {MD_PROC_SWAPS,"swaps",swaps,sizeof(swaps)-1},
    /* A userspace guest does not boot its own kernel. */
    {MD_PROC_KERNEL_CMDLINE,"cmdline","\n",1},
    /* No block drivers are exposed by the guest device inventory. File IO
     * still consumes backing storage; these are not host-wide IO counters. */
    {MD_PROC_DISKSTATS,"diskstats","",0},
    {MD_PROC_MODULES,"modules","",0},
    /* Optional provider selected only when the native proc file is denied. */
    {MD_PROC_UPTIME,"uptime","",0},
    {MD_PROC_SYSTEM_STAT,"stat","",0}
};
ino_t md_proc_static_inode(const char *name) {
    for (size_t i=0;i<sizeof(entries)/sizeof(*entries);++i)
        if (md_proc_static(entries[i].kind) && !strcmp(name,entries[i].name)) return (ino_t)i+1;
    return 0;
}
struct static_file {
    atomic_uint references;
    struct md_user_namespace *owner;
    struct md_fs_anchor *origin;
    const struct static_entry *entry;
    const char *text;
    size_t size;
    char sample[192];
    struct stat node;
    struct md_cgroup_member *cgroup;
    long ticks,cpus;
};
static void *retain(void *context) {
    struct static_file *c=context;
    atomic_fetch_add_explicit(&c->references,1,memory_order_relaxed); return c;
}
static void release(void *context) {
    struct static_file *c=context;
    if (atomic_fetch_sub_explicit(&c->references,1,memory_order_acq_rel)!=1) return;
    md_fs_anchor_release(c->origin); md_user_namespace_release(c->owner);
    md_cgroup_member_release(c->cgroup); free(c);
}
static struct md_fs_anchor *origin(void *context) { return ((struct static_file *)context)->origin; }
static int metadata(void *context,struct stat *st) {
    struct static_file *c=context;
    *st=(struct stat){.st_dev=UINT32_C(0xfffa0000),.st_ino=(ino_t)(c->entry-entries)+1,.st_mode=S_IFREG|0444,
        .st_nlink=1,.st_blksize=4096,.st_uid=md_user_namespace_into(c->owner,0,0),
        .st_gid=md_user_namespace_into(c->owner,1,0)};
    if (md_proc_host_sample(c->entry->kind)) {
        st->st_dev=c->node.st_dev; st->st_ino=c->node.st_ino;
    }
    return 0;
}
static int access_mode(void *context,const struct md_identity *id,unsigned mode) {
    (void)context; (void)id;
    return mode&~7U ? -EINVAL : mode&(W_OK|X_OK) ? -EACCES : 0;
}
static long read_value(void *context,const struct md_control_actor *actor,void *out,size_t size,int64_t offset) {
    struct static_file *file=context;
    (void)actor;
    if (offset<0) return -EINVAL;
    if (md_proc_host_sample(file->entry->kind) && size && (!offset || !file->size)) {
        struct timespec boot;
        if (clock_gettime(CLOCK_BOOTTIME,&boot)) return -errno;
        /* Host-wide CPU idle accounting is not exposed by an app's clock.
         * Keep that field zero, not an invented load or elapsed-time estimate. */
        int n;
        if (file->entry->kind==MD_PROC_UPTIME)
            n=snprintf(file->sample,sizeof(file->sample),"%lld.%02ld 0.00\n",
                (long long)boot.tv_sec,boot.tv_nsec/10000000);
        else {
            uint64_t user,system;
            int error=md_cgroup_cpu_usage(file->cgroup,&user,&system);
            if (error) return error;
            /* Sampling may take time; use clocks after the task snapshot. */
            if (clock_gettime(CLOCK_BOOTTIME,&boot)) return -errno;
            struct timespec wall;
            if (clock_gettime(CLOCK_REALTIME,&wall)) return -errno;
            /* Capacity unused by this launch, not Android's physical idle time.
             * Other host workloads are outside this accounting domain. */
            uint64_t capacity=((uint64_t)boot.tv_sec*file->ticks
                +(uint64_t)boot.tv_nsec*file->ticks/1000000000)*file->cpus;
            uint64_t idle=capacity>user+system ? capacity-user-system : 0;
            long long epoch=(long long)wall.tv_sec-boot.tv_sec-(wall.tv_nsec<boot.tv_nsec);
            n=snprintf(file->sample,sizeof(file->sample),"cpu %" PRIu64 " 0 %" PRIu64 " %" PRIu64
                " 0 0 0 0 0 0\nbtime %lld\n",user,system,idle,epoch);
        }
        if (n<0 || (size_t)n>=sizeof(file->sample)) return -EOVERFLOW;
        file->text=file->sample; file->size=(size_t)n;
    }
    if ((uint64_t)offset>=file->size) return 0;
    size_t count=file->size-(size_t)offset;
    if (count>size) count=size;
    memcpy(out,file->text+offset,count); return (long)count;
}
static const struct md_control_file_ops ops={.retain=retain,.release=release,.stat=metadata,
    .access=access_mode,.read=read_value,.origin=origin,.filesystem=PROC_SUPER_MAGIC};
struct md_control_file md_proc_static_new(enum md_proc_kind kind,struct md_user_namespace *owner,struct md_fs_anchor *anchor,
        struct md_network_namespace *network) {
    const struct static_entry *entry=NULL;
    for (size_t i=0;i<sizeof(entries)/sizeof(*entries);++i)
        if (entries[i].kind==kind) { entry=&entries[i]; break; }
    if (!entry) { errno=EINVAL; return (struct md_control_file){0}; }
    struct static_file *c=calloc(1,sizeof(*c));
    if (!c) return (struct md_control_file){0};
    atomic_init(&c->references,1);
    c->owner=md_user_namespace_retain(owner); c->origin=md_fs_anchor_retain(anchor);
    c->entry=entry;
    c->text=entry->text; c->size=entry->size;
    if (kind==MD_PROC_MODULES && md_network_namespace_managed(network)) {
        c->text=network_modules; c->size=sizeof(network_modules)-1;
    }
    return (struct md_control_file){&ops,c};
}
struct md_control_file md_proc_host_sample_new(enum md_proc_kind kind,struct md_user_namespace *owner,struct md_fs_anchor *anchor,int fd,
        struct md_cgroup_member *member) {
    if (!md_proc_host_sample(kind)) { errno=EINVAL; return (struct md_control_file){0}; }
    if (kind==MD_PROC_SYSTEM_STAT && !member) { errno=EINVAL; return (struct md_control_file){0}; }
    struct md_control_file file=md_proc_static_new(kind,owner,anchor,NULL);
    if (file.ops && kind==MD_PROC_SYSTEM_STAT) {
        struct static_file *c=file.context;
        c->cgroup=md_cgroup_member_retain(member);
        c->ticks=sysconf(_SC_CLK_TCK); c->cpus=sysconf(_SC_NPROCESSORS_CONF);
        if (c->ticks<=0 || c->cpus<=0) { md_control_file_release(file); errno=EIO; return (struct md_control_file){0}; }
    }
    if (file.ops && fstat(fd,&((struct static_file *)file.context)->node)) {
        int error=errno;
        md_control_file_release(file); errno=error; return (struct md_control_file){0};
    }
    return file;
}
