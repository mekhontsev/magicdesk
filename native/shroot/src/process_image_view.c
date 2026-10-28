#define _GNU_SOURCE
#include "process_image_view.h"
#include "proc_paths.h"
#include "control_file.h"
#include "program_files.h"
#include "arguments.h"
#include "process_arguments.h"
#include "task_memory.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/uio.h>
#include <linux/magic.h>
#include <stdatomic.h>
#include <unistd.h>

struct md_process_executable { atomic_uint references; int fd; };
struct md_process_executable *md_process_executable_new(int fd) {
    struct md_process_executable *image=malloc(sizeof(*image));
    if (!image) return NULL;
    int retained=fcntl(fd,F_DUPFD_CLOEXEC,0);
    if (retained<0) { int error=errno; free(image); errno=error; return NULL; }
    atomic_init(&image->references,1); image->fd=retained; return image;
}
struct md_process_executable *md_process_executable_retain(struct md_process_executable *image) {
    if (image) atomic_fetch_add_explicit(&image->references,1,memory_order_relaxed);
    return image;
}
void md_process_executable_release(struct md_process_executable *image) {
    if (!image || atomic_fetch_sub_explicit(&image->references,1,memory_order_acq_rel)!=1) return;
    close(image->fd); free(image);
}
static void *image_retain(void *image) { return md_process_executable_retain(image); }
static void image_release(void *image) { md_process_executable_release(image); }
static int image_stat(void *context,struct stat *st) {
    struct md_process_executable *image=context;
    return fstat(image->fd,st) ? -errno : 0;
}
static long image_read(void *context,const struct md_control_actor *actor,void *out,size_t size,int64_t offset) {
    (void)actor;
    struct md_process_executable *image=context;
    ssize_t result=pread(image->fd,out,size,offset);
    return result<0 ? -errno : result;
}
static int image_access(void *context,const struct md_identity *identity,unsigned mode) {
    (void)context; (void)identity;
    return mode&W_OK ? -ETXTBSY : 0;
}
static int image_descriptor(void *context,int flags) {
    struct md_process_executable *image=context;
    int fd;
    if (flags&O_PATH) {
        char path[64]; snprintf(path,sizeof(path),"/proc/self/fd/%d",image->fd);
        fd=open(path,O_PATH|O_CLOEXEC);
    } else fd=fcntl(image->fd,F_DUPFD_CLOEXEC,0);
    return fd<0 ? -errno : fd;
}
static int image_seals(void *context) {
    struct md_process_executable *image=context;
    int seals=fcntl(image->fd,F_GET_SEALS);
    return seals<0 ? -errno : seals;
}
static const struct md_control_file_ops image_ops={.retain=image_retain,.release=image_release,
    .stat=image_stat,.read=image_read,.access=image_access,.filesystem=TMPFS_MAGIC,
    .descriptor=image_descriptor,.seals=image_seals,.mappable=1};
struct md_control_file md_process_executable_view(struct md_process_executable *image) {
    return (struct md_control_file){image ? &image_ops : NULL,image};
}
static int copy(pid_t pid, uintptr_t address, void *data, size_t size,int memory) {
    ssize_t n=md_task_read_memory(pid,address,data,size,memory);
    return n==(ssize_t)size ? 0 : n==-EPERM || n==-EACCES ? -EACCES : -EFAULT;
}
struct argument_source { pid_t pid; int memory; };
static long read_arguments(void *context,uintptr_t address,void *out,size_t size) {
    struct argument_source *source=context;
    return md_task_read_memory(source->pid,address,out,size,source->memory);
}
ssize_t md_process_image_link(pid_t pid, uintptr_t image, int memory, char *out, size_t capacity) {
    if (!image) return -ENOENT;
    if (!out || !capacity || image>UINTPTR_MAX-sizeof(struct md_process_image)) return -EINVAL;
    uintptr_t path=0;
    int error=copy(pid,image+offsetof(struct md_process_image,executable_path),&path,sizeof(path),memory);
    if (error) return error;
    ssize_t length=md_task_read_string_from(pid,path,out,capacity,(size_t)sysconf(_SC_PAGESIZE),memory);
    if (length==-EPERM || length==-EACCES) return -EACCES;
    return length<0 && length!=-ENAMETOOLONG ? -EFAULT : length;
}
int md_process_image_identity(pid_t pid,uintptr_t image,int memory,struct md_image_identity *out) {
    memset(out,0,sizeof(*out));
    ssize_t result=md_process_image_link(pid,image,memory,out->path,sizeof(out->path));
    if (result<0) return (int)result;
    int error=copy(pid,image+offsetof(struct md_process_image,executable_object),out->object,sizeof(out->object),memory);
    if (!error && out->object[sizeof(out->object)-1]) error=-EPROTO;
    return error;
}
static int empty_cmdline(pid_t pid,unsigned kind,int error) {
    if (kind!=MD_PROC_CMDLINE) return error;
    /* A zombie can outlive its supervised thread and mm. Only kernel EOF is
     * an empty public view; never read bytes from a potentially reused PID. */
    char path[64],byte;
    snprintf(path,sizeof(path),"/proc/%d/cmdline",pid);
    int fd=open(path,O_RDONLY|O_CLOEXEC);
    if (fd<0) return error;
    ssize_t size=pread(fd,&byte,1,0);
    close(fd);
    return size==0 ? 0 : error;
}
ssize_t md_process_image_snapshot(pid_t pid, uintptr_t image, int memory, unsigned kind, void **out) {
    if (!out || *out) return -EINVAL;
    if (kind!=MD_PROC_AUXV && kind!=MD_PROC_CMDLINE) return -ENOTSUP;
    if (!image) return empty_cmdline(pid,kind,-ENOENT);
    struct md_process_image *view=malloc(sizeof(*view));
    if (!view) return -ENOMEM;
    /* Cmdline is public even when ptrace access has been revoked. Never lend
     * this retained authority to auxv or executable inspection. */
    if (kind!=MD_PROC_CMDLINE) memory=-1;
    int r=copy(pid,image,view,sizeof(*view),memory);
    if (!r && (view->argument_bytes>MD_EXEC_BYTES || view->environment_bytes>MD_EXEC_BYTES
            || view->auxv_bytes>sizeof(view->auxv) || view->executable_object[32])) r=-EPROTO;
    size_t capacity=0,size=0;
    size_t page_size=(size_t)sysconf(_SC_PAGESIZE);
    if (!r) {
        capacity=kind==MD_PROC_AUXV ? view->auxv_bytes : view->argument_bytes+view->environment_bytes;
        if (kind==MD_PROC_CMDLINE && capacity>page_size) {
            capacity=view->argument_bytes>page_size ? view->argument_bytes : page_size;
        }
    }
    char *bytes=r ? NULL : malloc(capacity ? capacity : 1);
    if (!r && !bytes) r=-ENOMEM;
    if (!r && kind==MD_PROC_AUXV) {
        memcpy(bytes,view->auxv,view->auxv_bytes); size=view->auxv_bytes;
    } else if (!r) {
        struct argument_source source={pid,memory};
        while (size<capacity) {
            size_t count=capacity-size;
            if (count>4096) count=4096;
            long n=md_process_arguments_read(view,page_size,read_arguments,&source,bytes+size,count,size);
            if (n<=0) { r=(int)n; break; }
            size+=(size_t)n;
        }
    }
    free(view);
    if (r) {
        free(bytes);
        if (!empty_cmdline(pid,kind,r)) return 0;
    } else *out=bytes;
    return r ? r : (ssize_t)size;
}
