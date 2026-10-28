#define _GNU_SOURCE
#include "full_device.h"
#include "fs_anchor.h"
#include "file_open.h"
#include "file_permissions.h"
#include <errno.h>
#include <fcntl.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <sys/sysmacros.h>
#include <unistd.h>

struct full_device { atomic_uint references; struct stat metadata; struct md_fs_anchor *origin; };
static void *retain(void *context) {
    struct full_device *f=context;
    atomic_fetch_add_explicit(&f->references,1,memory_order_relaxed); return f;
}
static void release(void *context) {
    struct full_device *f=context;
    if (atomic_fetch_sub_explicit(&f->references,1,memory_order_acq_rel)==1) {
        md_fs_anchor_release(f->origin); free(f);
    }
}
static int metadata(void *context,struct stat *st) {
    struct full_device *f=context; *st=f->metadata; return 0;
}
static int access_mode(void *context,const struct md_identity *identity,unsigned mode) {
    struct full_device *f=context; return md_file_permissions(&f->metadata,mode,identity);
}
static long io(void *context,const struct md_control_actor *actor,unsigned operation,void *data,
        size_t size,int64_t offset,struct md_control_pending **pending) {
    (void)context; (void)actor; (void)offset; (void)pending;
    if (operation==MD_CONTROL_WRITE) return -ENOSPC;
    if (operation!=MD_CONTROL_READ) return -ENOTDIR;
    if (size) memset(data,0,size);
    return (long)size;
}
static size_t read_size(void *context) { (void)context; return MD_CONTROL_IO_MAX; }
static long seek(void *context,const struct md_control_actor *actor,int64_t offset,int whence,
        struct md_control_pending **pending) {
    (void)context; (void)actor; (void)offset; (void)pending;
    return whence<SEEK_SET || whence>SEEK_HOLE ? -EINVAL : 0;
}
static int descriptor(void *context,int flags) {
    (void)context;
    /* The carrier supplies native readiness, never /dev/full data semantics. */
    struct md_open_completion completion;
    return md_open_device(S_IFCHR,makedev(1,3),flags,&completion);
}
static struct md_fs_anchor *origin(void *context) {
    struct full_device *f=context; return f->origin;
}
static const struct md_control_file_ops ops={.retain=retain,.release=release,.stat=metadata,
    .access=access_mode,.io=io,.read_size=read_size,.seek=seek,.descriptor=descriptor,
    .origin=origin,.filesystem_paths=1};
struct md_control_file md_full_device_new(const struct stat *st,struct md_fs_anchor *origin) {
    struct full_device *f=calloc(1,sizeof(*f));
    if (f) {
        atomic_init(&f->references,1); f->metadata=*st; f->origin=md_fs_anchor_retain(origin);
    }
    return (struct md_control_file){f ? &ops : NULL,f};
}
