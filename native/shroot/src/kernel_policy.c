#include "kernel_policy.h"
#include "namespace_identity.h"
#include "guest_identity.h"
#include "fs_anchor.h"
#include "mount_policy.h"
#include "sysctl_value.h"
#include <errno.h>
#include <linux/magic.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

struct md_kernel_policy {
    atomic_uint references;
    uint64_t inode;
    atomic_uint values[MD_KERNEL_POLICY_COUNT];
};
struct policy_file {
    atomic_uint references;
    struct md_kernel_policy *policy;
    struct md_fs_anchor *origin;
    unsigned index;
};
struct md_kernel_policy *md_kernel_policy_new(void) {
    struct md_kernel_policy *p=calloc(1,sizeof(*p));
    if (!p) return NULL;
    int error=md_namespace_identity_new(&p->inode);
    if (error) { free(p); errno=-error; return NULL; }
    atomic_init(&p->references,1);
    for (unsigned i=0;i<MD_KERNEL_POLICY_COUNT;++i) atomic_init(&p->values[i],md_kernel_policy_specs[i].initial);
    return p;
}
struct md_kernel_policy *md_kernel_policy_retain(struct md_kernel_policy *p) {
    if (p) atomic_fetch_add_explicit(&p->references,1,memory_order_relaxed);
    return p;
}
void md_kernel_policy_release(struct md_kernel_policy *p) {
    if (p && atomic_fetch_sub_explicit(&p->references,1,memory_order_acq_rel)==1) free(p);
}
static void *retain(void *context) {
    struct policy_file *f=context; atomic_fetch_add_explicit(&f->references,1,memory_order_relaxed); return f;
}
static void release(void *context) {
    struct policy_file *f=context;
    if (atomic_fetch_sub_explicit(&f->references,1,memory_order_acq_rel)!=1) return;
    md_kernel_policy_release(f->policy); md_fs_anchor_release(f->origin); free(f);
}
static int metadata(void *context,struct stat *st) {
    struct policy_file *f=context;
    *st=(struct stat){.st_dev=UINT32_C(0xfff90000),.st_ino=f->policy->inode+f->index,
        .st_mode=S_IFREG|0644,.st_nlink=1,.st_blksize=4096}; return 0;
}
static int access_mode(void *context,const struct md_identity *id,unsigned mode) {
    struct policy_file *f=context;
    if (mode&~7U) return -EINVAL;
    if (mode&X_OK) return -EACCES;
    if ((mode&W_OK) && (md_fs_anchor_policy(f->origin)&MD_MOUNT_READONLY)) return -EROFS;
    /* Global sysctls are not delegated to nested user-namespace root. */
    return (mode&W_OK) && (!id || id->uid.effective) ? -EACCES : 0;
}
static long read_value(void *context,const struct md_control_actor *actor,void *out,size_t size,int64_t offset) {
    (void)actor; struct policy_file *f=context;
    if (offset<0) return -EINVAL;
    char text[32];
    int length=snprintf(text,sizeof(text),"%u\n",atomic_load_explicit(&f->policy->values[f->index],memory_order_relaxed));
    if ((uint64_t)offset>=(unsigned)length) return 0;
    size_t count=(size_t)length-(size_t)offset;
    if (count>size) count=size;
    memcpy(out,text+offset,count); return (long)count;
}
static long write_value(void *context,const struct md_control_actor *actor,const void *data,size_t size,int64_t offset) {
    struct policy_file *f=context;
    int error=access_mode(f,actor->identity,W_OK);
    if (error || !size) return error;
    if (offset<0) return -EINVAL;
    if (offset) return (long)size;
    uint32_t value;
    error=md_sysctl_parse_uint(data,size,&value);
    if (!error && value>md_kernel_policy_specs[f->index].maximum) error=-EINVAL;
    /* Strict commit accounting would require mmap/brk admission, not a sysctl
     * acknowledgement. Keep it explicitly unsupported. */
    if (!error && f->index==0 && value!=1) error=-EOPNOTSUPP;
    if (!error) atomic_store_explicit(&f->policy->values[f->index],value,memory_order_relaxed);
    return error ? error : (long)size;
}
static struct md_fs_anchor *origin(void *context) { return ((struct policy_file *)context)->origin; }
static const struct md_control_file_ops ops={.retain=retain,.release=release,.stat=metadata,
    .access=access_mode,.read=read_value,.write=write_value,.origin=origin,.filesystem=PROC_SUPER_MAGIC};
struct md_control_file md_kernel_policy_file(struct md_kernel_policy *policy,unsigned index,struct md_fs_anchor *anchor) {
    if (!policy || index>=MD_KERNEL_POLICY_COUNT) { errno=EINVAL; return (struct md_control_file){0}; }
    struct policy_file *f=calloc(1,sizeof(*f));
    if (!f) return (struct md_control_file){0};
    atomic_init(&f->references,1); f->policy=md_kernel_policy_retain(policy);
    f->origin=md_fs_anchor_retain(anchor); f->index=index;
    return (struct md_control_file){&ops,f};
}
