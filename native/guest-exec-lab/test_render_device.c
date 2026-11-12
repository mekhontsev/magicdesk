#define _GNU_SOURCE
#include "render_device.h"
#include "control_ioctl.h"
#include <assert.h>
#include <drm/drm.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/dma-buf.h>
#include <linux/dma-heap.h>
#include <stdarg.h>
#include <stdio.h>
#include <sys/eventfd.h>
#include <sys/mman.h>
#include <sys/sysmacros.h>
#include <unistd.h>

static int failure, calls, descriptors[32], count, bridge_error, node_error, all_nodes;
int __real_stat(const char *,struct stat *);
int __wrap_stat(const char *path,struct stat *st) {
    if (strncmp(path,"/dev/dri/renderD",15)) return __real_stat(path,st);
    if (node_error && (all_nodes || !strcmp(path,"/dev/dri/renderD128"))) {
        errno=node_error; return -1;
    }
    errno=ENOENT; return -1;
}
static int tracked(int fd) { assert(fd>=0 && count<32); descriptors[count++]=fd; return fd; }
int __wrap_open(const char *path,int flags,...) {
    (void)flags;
    assert(!strcmp(path,"/dev/kgsl-3d0") || !strcmp(path,"/dev/dma_heap/system"));
    if (++calls==failure) { errno=EACCES; return -1; }
    return tracked(memfd_create("render-probe-test",MFD_CLOEXEC));
}
int __wrap_ioctl(int fd,unsigned long command,...) {
    assert(fd>=0);
    va_list ap; va_start(ap,command); void *argument=va_arg(ap,void *); va_end(ap);
    if (++calls==failure) { errno=ENOTTY; return -1; }
    if (command==DMA_BUF_IOCTL_EXPORT_SYNC_FILE && bridge_error) { errno=bridge_error; return -1; }
    if (command==DMA_BUF_IOCTL_SYNC) {
        assert(((struct dma_buf_sync *)argument)->flags==UINT64_MAX);
        errno=EINVAL; return -1;
    }
    if (command==DMA_HEAP_IOCTL_ALLOC) {
        struct dma_heap_allocation_data *a=argument;
        assert(a->len==4096 && a->fd_flags==(O_RDWR|O_CLOEXEC));
        a->fd=(unsigned)tracked(memfd_create("buffer",MFD_CLOEXEC));
    } else if (command==DMA_BUF_IOCTL_EXPORT_SYNC_FILE) {
        struct dma_buf_export_sync_file *a=argument;
        assert(a->flags==DMA_BUF_SYNC_RW);
        a->fd=tracked(eventfd(1,EFD_CLOEXEC));
    } else {
        assert(command==DMA_BUF_IOCTL_IMPORT_SYNC_FILE);
        struct dma_buf_import_sync_file *a=argument;
        assert(a->flags==DMA_BUF_SYNC_WRITE && fcntl(a->fd,F_GETFD)>=0);
    }
    return 0;
}
static void closed(void) {
    for (int i=0;i<count;++i) assert(fcntl(descriptors[i],F_GETFD)==-1 && errno==EBADF);
    count=0; calls=0;
}
int main(void) {
    for (failure=1;failure<=5;++failure) {
        struct md_control_file f={0};
        assert(md_render_device_admit(&f,0)<0 && !f.ops);
        assert(calls==failure);
        closed();
    }
    failure=0;
    struct md_control_file f={0};
    assert(md_render_device_admit(&f,1)==0 && calls==5 && md_render_device_is_node(f));
    struct stat st;
    assert(!f.ops->stat(f.context,&st) && S_ISCHR(st.st_mode) && st.st_rdev==makedev(226,128));
    struct md_control_ioctl spec;
    assert(!f.ops->ioctl_spec(f.context,DRM_IOCTL_VERSION,&spec));
    long size=md_control_ioctl_size(&spec);
    assert(size>(long)sizeof(struct drm_version) && size<=MD_CONTROL_IOCTL_MAX);
    unsigned char data[512]={0};
    struct drm_version *version=(void *)data;
    version->name=(char *)1; version->name_len=SIZE_MAX;
    assert(!f.ops->ioctl(f.context,NULL,DRM_IOCTL_VERSION,1,data,(size_t)size));
    assert(version->version_major==1 && version->name==(char *)1 && version->name_len==6);
    assert(!memcmp(data+sizeof(*version),"shroot",6));
    assert(f.ops->ioctl_spec(f.context,DRM_IOCTL_GET_CAP,&spec)==-ENOTTY);
    assert(f.ops->access(f.context,NULL,X_OK)==-EACCES);
    assert(!f.ops->access(f.context,NULL,R_OK|W_OK));
    int fd=f.ops->descriptor(f.context,O_RDWR); assert(fd>=0); close(fd);
    md_control_file_release(f); closed();

    for (unsigned i=0;i<2;++i) {
        node_error=(int[]){EACCES,EPERM}[i];
        f=(struct md_control_file){0};
        assert(!md_render_device_admit(&f,0) && md_render_device_minor(f)==129);
        assert(!f.ops->stat(f.context,&st) && st.st_rdev==makedev(226,129));
        md_control_file_release(f); closed();
        all_nodes=1;
        f=(struct md_control_file){0};
        assert(md_render_device_admit(&f,0)==-node_error && !f.ops && !calls);
        closed(); all_nodes=0;
    }
    node_error=EIO;
    f=(struct md_control_file){0};
    assert(md_render_device_admit(&f,0)==-EIO && !f.ops && !calls);
    closed(); node_error=0;

    for (unsigned i=0;i<3;++i) {
        bridge_error=(int[]){ENOTTY,ENOSYS,EPERM}[i];
        f=(struct md_control_file){0};
        assert(md_render_device_admit(&f,0)==-bridge_error && !f.ops); closed();
        int error=md_render_device_admit(&f,1);
        if (bridge_error==EPERM) assert(error==-EPERM && !f.ops);
        else { assert(!error && md_render_device_is_node(f)); md_control_file_release(f); }
        closed();
    }

    struct md_control_ioctl s={.size=16,.direction=MD_CONTROL_IN|MD_CONTROL_OUT};
    assert(md_control_ioctl_size(&s)==16);
    struct md_control_ioctl constant={.size=4,.direction=MD_CONTROL_OUT|MD_CONTROL_CONST,.value=0x12345678};
    unsigned output=0;
    assert(md_control_ioctl_size(&constant)==4 && md_control_ioctl_constant(&constant,&output));
    assert(output==0x12345678);
    constant.direction|=MD_CONTROL_IN;
    assert(md_control_ioctl_size(&constant)==-EPROTO);
    constant.direction=MD_CONTROL_OUT|MD_CONTROL_CONST; constant.size=9;
    assert(md_control_ioctl_size(&constant)==-EPROTO);
    s.fields=1; assert(md_control_ioctl_size(&s)==-EPROTO);
    s.direction|=MD_CONTROL_INDIRECT;
    s.field[0]=(struct md_control_ioctl_field){0,8,32,MD_CONTROL_OUT};
    assert(md_control_ioctl_size(&s)==48);
    s.field[0].pointer=9; assert(md_control_ioctl_size(&s)==-EPROTO);
    s.field[0].pointer=0; s.field[0].length=UINT32_MAX; assert(md_control_ioctl_size(&s)==-EPROTO);
    s.field[0].length=8; s.field[0].capacity=UINT32_MAX; assert(md_control_ioctl_size(&s)==-EPROTO);
    s.field[0].capacity=32; s.fields=MD_CONTROL_IOCTL_FIELDS+1; assert(md_control_ioctl_size(&s)==-EPROTO);
    puts("PASS render admission failure cleanup, typed discovery and bounded ioctl layout");
    return 0;
}
