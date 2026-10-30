#define _GNU_SOURCE
#include "fuse_device.h"
#include "fuse_transport.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/magic.h>
#include <poll.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <sys/sysmacros.h>
#include <unistd.h>

struct device {
    atomic_uint references;
    struct md_fuse_connection *connection;
};
static const struct md_control_file_ops operations;
static void *retain(void *context) {
    struct device *d=context; atomic_fetch_add_explicit(&d->references,1,memory_order_relaxed); return d;
}
static void release(void *context) {
    struct device *d=context;
    if (atomic_fetch_sub_explicit(&d->references,1,memory_order_acq_rel)!=1) return;
    if (d->connection) {
        md_fuse_connection_abort(d->connection,-ECONNABORTED);
        md_fuse_connection_release(d->connection);
    }
    free(d);
}
static struct device *create(void) {
    struct device *d=calloc(1,sizeof(*d));
    if (d) atomic_init(&d->references,1);
    return d;
}
int md_fuse_device_node(struct md_control_file *out) {
    struct device *d=create(); if (!d) return -ENOMEM;
    *out=(struct md_control_file){&operations,d}; return 0;
}
struct md_fuse_connection *md_fuse_device_connection(struct md_control_file f) {
    return f.ops==&operations ? ((struct device *)f.context)->connection : NULL;
}
int md_fuse_device_is_node(struct md_control_file f) {
    return f.ops==&operations && !((struct device *)f.context)->connection;
}
static void *open_device(void *context,const struct md_control_opener *opener,int flags) {
    (void)opener; (void)context;
    if ((flags&O_ACCMODE)!=O_RDWR || (flags&(O_CREAT|O_TRUNC|O_DIRECTORY))) { errno=EINVAL; return NULL; }
    struct device *d=create(); if (!d) return NULL;
    int error=md_fuse_connection_new(&d->connection);
    if (error) { release(d); errno=-error; return NULL; }
    return d;
}
static int stat_device(void *context,struct stat *st) {
    (void)context;
    *st=(struct stat){.st_mode=S_IFCHR|0666,.st_nlink=1,.st_ino=229,
        .st_dev=makedev(0,99),.st_rdev=makedev(10,229),.st_blksize=4096}; return 0;
}
static int access_device(void *context,const struct md_identity *id,unsigned mode) {
    (void)context; (void)id; return mode&X_OK ? -EACCES : 0;
}
static int descriptor(void *context,int flags) {
    struct device *d=context;
    int fd=(flags&O_PATH) ? open("/dev/null",O_PATH|O_CLOEXEC)
        : !d->connection ? -1 : fcntl(md_fuse_connection_descriptor(d->connection),F_DUPFD_CLOEXEC,3);
    return fd<0 ? (!d->connection && !(flags&O_PATH) ? -EBADF : -errno) : fd;
}
static long read_device(void *context,const struct md_control_actor *actor,void *data,size_t size,int64_t offset) {
    (void)offset; struct device *d=context;
    if (!d->connection) return -EBADF;
    md_fuse_connection_reader(d->connection,actor ? actor->wakeup : NULL);
    return md_fuse_connection_read(d->connection,data,size);
}
static void read_commit(void *context,size_t size) {
    struct device *d=context;
    if (d->connection) md_fuse_connection_read_commit(d->connection,size);
}
static long write_device(void *context,const struct md_control_actor *actor,const void *data,size_t size,int64_t offset) {
    (void)actor; (void)offset; struct device *d=context;
    return d->connection ? md_fuse_connection_write(d->connection,data,size) : -EBADF;
}
static long poll_device(void *context) {
    struct device *d=context;
    return d->connection ? md_fuse_connection_poll(d->connection) : POLLERR;
}
static int readable(void *context) { return !!(poll_device(context)&(POLLIN|POLLERR|POLLHUP)); }
static int poll_descriptor(void *context,int *out) {
    struct device *d=context;
    *out=d->connection ? md_fuse_connection_descriptor(d->connection) : -1; return 0;
}
static const struct md_control_file_ops operations={.retain=retain,.release=release,
    .stat=stat_device,.access=access_device,.open=open_device,.descriptor=descriptor,
    .read=read_device,.read_commit=read_commit,.write=write_device,.nonseekable=1,
    .filesystem=TMPFS_MAGIC,.splice=1,.readable=readable,.poll=poll_device,.poll_descriptor=poll_descriptor};
