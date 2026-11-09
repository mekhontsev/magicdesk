#define _GNU_SOURCE
#include "render_device.h"
#include "control_ioctl.h"
#include <drm/drm.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/dma-buf.h>
#include <linux/dma-heap.h>
#include <linux/magic.h>
#include <poll.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/sysmacros.h>
#include <unistd.h>

#ifndef DMA_BUF_IOCTL_EXPORT_SYNC_FILE
struct dma_buf_export_sync_file { __u32 flags; __s32 fd; };
struct dma_buf_import_sync_file { __u32 flags; __s32 fd; };
#define DMA_BUF_IOCTL_EXPORT_SYNC_FILE _IOWR(DMA_BUF_BASE,2,struct dma_buf_export_sync_file)
#define DMA_BUF_IOCTL_IMPORT_SYNC_FILE _IOW(DMA_BUF_BASE,3,struct dma_buf_import_sync_file)
#endif

struct device { atomic_uint refs; int heap; unsigned minor; };
static const struct md_control_file_ops operations;
static void *retain(void *context) {
    struct device *d=context; atomic_fetch_add_explicit(&d->refs,1,memory_order_relaxed); return d;
}
static void release(void *context) {
    struct device *d=context;
    if (atomic_fetch_sub_explicit(&d->refs,1,memory_order_acq_rel)!=1) return;
    close(d->heap); free(d);
}
static int create(int heap,unsigned minor,struct md_control_file *out) {
    struct device *d=calloc(1,sizeof(*d));
    if (!d) { close(heap); return -ENOMEM; }
    atomic_init(&d->refs,1); d->heap=heap; d->minor=minor;
    *out=(struct md_control_file){&operations,d}; return 0;
}
int md_render_device_restore(unsigned minor,struct md_control_file *out) {
    if (minor<128 || minor>=192) return -EINVAL;
    int fd=open("/dev/dma_heap/system",O_RDONLY|O_CLOEXEC);
    return fd<0 ? -errno : create(fd,minor,out);
}
int md_render_device_admit(struct md_control_file *out,int completion_owned) {
    /* Never replace an actual DRM render device. KGSL remains the GPU owner. */
    struct stat st; unsigned number; int denied=0;
    for (number=128;number<192;++number) {
        char path[64]; snprintf(path,sizeof(path),"/dev/dri/renderD%u",number);
        if (!stat(path,&st)) continue;
        /* An inaccessible native node is occupied, not proof that every
         * later minor is unavailable. Only ENOENT/ENOTDIR admits a new node. */
        if (errno==EACCES || errno==EPERM) { denied=errno; continue; }
        if (errno!=ENOENT && errno!=ENOTDIR) return -errno;
        break;
    }
    if (number==192) return denied ? -denied : -ENOSPC;
    int gpu=open("/dev/kgsl-3d0",O_RDWR|O_CLOEXEC);
    if (gpu<0) return -errno;
    close(gpu);
    int heap=open("/dev/dma_heap/system",O_RDONLY|O_CLOEXEC);
    if (heap<0) return -errno;
    struct dma_heap_allocation_data allocation={.len=4096,.fd_flags=O_RDWR|O_CLOEXEC};
    int error=ioctl(heap,DMA_HEAP_IOCTL_ALLOC,&allocation) ? -errno : 0;
    int buffer=error ? -1 : (int)allocation.fd;
    struct dma_buf_export_sync_file exported={.flags=DMA_BUF_SYNC_RW,.fd=-1};
    if (!error && ioctl(buffer,DMA_BUF_IOCTL_EXPORT_SYNC_FILE,&exported)) error=-errno;
    if (completion_owned && (error==-ENOTTY || error==-ENOSYS) && buffer>=0) {
        /* A prepared Mesa completes GPU access before protocol handoff; its
         * consumer retains the buffer through completion. Do not admit this
         * contract for an older recipe or reinterpret a permission denial. */
        struct dma_buf_sync invalid={.flags=UINT64_MAX};
        if (ioctl(buffer,DMA_BUF_IOCTL_SYNC,&invalid)==-1 && errno==EINVAL) {
            struct pollfd ready={.fd=buffer,.events=POLLOUT};
            int r=poll(&ready,1,0);
            error=r<0 ? -errno : r==1 && (ready.revents&POLLOUT)
                && !(ready.revents&(POLLERR|POLLHUP|POLLNVAL)) ? 0 : -EIO;
            if (!error) {
                close(buffer);
                return create(heap,number,out);
            }
        }
    }
    if (!error) {
        struct pollfd ready={.fd=exported.fd,.events=POLLIN};
        int r=poll(&ready,1,0);
        if (r<0) error=-errno;
        else if (r!=1 || ready.revents!=POLLIN) error=-EIO;
    }
    struct dma_buf_import_sync_file imported={.flags=DMA_BUF_SYNC_WRITE,.fd=exported.fd};
    if (!error && ioctl(buffer,DMA_BUF_IOCTL_IMPORT_SYNC_FILE,&imported)) error=-errno;
    if (exported.fd>=0) close(exported.fd);
    if (buffer>=0) close(buffer);
    if (error) { close(heap); return error; }
    return create(heap,number,out);
}
int md_render_device_is_node(struct md_control_file file) { return file.ops==&operations; }
unsigned md_render_device_minor(struct md_control_file file) {
    return md_render_device_is_node(file) ? ((struct device *)file.context)->minor : 0;
}
static int metadata(void *context,struct stat *st) {
    struct device *d=context;
    *st=(struct stat){.st_mode=S_IFCHR|0666,.st_nlink=1,.st_ino=d->minor,
        .st_dev=makedev(0,99),.st_rdev=makedev(226,d->minor),.st_blksize=4096}; return 0;
}
static int access_device(void *context,const struct md_identity *identity,unsigned mode) {
    (void)context; (void)identity; return mode&X_OK ? -EACCES : 0;
}
static int descriptor(void *context,int flags) {
    struct device *d=context;
    int fd=flags&O_PATH ? open("/dev/dma_heap/system",O_PATH|O_CLOEXEC)
        : fcntl(d->heap,F_DUPFD_CLOEXEC,3);
    return fd<0 ? -errno : fd;
}
static long io(void *context,const struct md_control_actor *actor,unsigned operation,
        void *data,size_t size,int64_t offset,struct md_control_pending **pending) {
    (void)context; (void)actor; (void)operation; (void)data; (void)size; (void)offset; (void)pending;
    return -EINVAL;
}
static const char *const version_strings[]={"shroot","20261019","Shroot KGSL buffer discovery"};
static int ioctl_spec(void *context,unsigned command,struct md_control_ioctl *out) {
    (void)context;
    if (command!=DRM_IOCTL_VERSION) return -ENOTTY;
    *out=(struct md_control_ioctl){.size=sizeof(struct drm_version),
        .direction=MD_CONTROL_IN|MD_CONTROL_OUT|MD_CONTROL_INDIRECT,.fields=3,
        .field={
            {offsetof(struct drm_version,name),offsetof(struct drm_version,name_len),0,MD_CONTROL_OUT},
            {offsetof(struct drm_version,date),offsetof(struct drm_version,date_len),0,MD_CONTROL_OUT},
            {offsetof(struct drm_version,desc),offsetof(struct drm_version,desc_len),0,MD_CONTROL_OUT},
        }};
    for (unsigned i=0;i<out->fields;++i) out->field[i].capacity=(unsigned)strlen(version_strings[i]);
    return 0;
}
static long ioctl_device(void *context,const struct md_control_actor *actor,unsigned command,
        unsigned long argument,void *data,size_t size) {
    (void)actor; (void)argument;
    struct md_control_ioctl spec;
    int error=ioctl_spec(context,command,&spec);
    if (error) return error;
    if (md_control_ioctl_size(&spec)!=(long)size) return -EINVAL;
    struct drm_version *v=data;
    v->version_major=1; v->version_minor=0; v->version_patchlevel=0;
    size_t offset=spec.size;
    for (unsigned i=0;i<spec.fields;++i) {
        size_t length=spec.field[i].capacity;
        memcpy((char *)data+offset,version_strings[i],length);
        memcpy((char *)data+spec.field[i].length,&length,sizeof(length));
        offset+=length;
    }
    return 0;
}
static const struct md_control_file_ops operations={.retain=retain,.release=release,
    .stat=metadata,.access=access_device,.descriptor=descriptor,.io=io,.nonseekable=1,
    .filesystem=TMPFS_MAGIC,.ioctl_spec=ioctl_spec,.ioctl=ioctl_device};
