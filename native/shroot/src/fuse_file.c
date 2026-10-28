#define _GNU_SOURCE
#include "fuse_view_internal.h"
#include "fuse_image.h"
#include "fs_control.h"
#include "resource_order.h"
#include "directory_record.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/magic.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/inotify.h>
#include <unistd.h>

struct fuse_file {
    atomic_uint references;
    struct fuse_view *view;
    struct fuse_node *node;
    struct fuse_name *name;
    struct md_fs_anchor *origin;
    struct md_identity opener;
    uint64_t handle;
    unsigned flags,open_flags;
    int opened;
    int published;
};
static void *file_retain(void *context) {
    struct fuse_file *f=context; atomic_fetch_add_explicit(&f->references,1,memory_order_relaxed); return f;
}
void md_fuse_file_release_remote(struct fuse_view *v,uint64_t node,int directory,const struct md_identity *identity,
        uint64_t handle,unsigned flags) {
    struct fuse_release_in in={.fh=handle,.flags=flags&~(O_CREAT|O_EXCL|O_TRUNC)};
    struct md_fuse_request *request=NULL;
    unsigned opcode=directory ? FUSE_RELEASEDIR : FUSE_RELEASE;
    int error=md_fuse_session_request(v->session,identity,0,opcode,node,&in,sizeof(in),0,0,NULL,&request);
    if (!error) md_fuse_request_release(request);
    else if (error==-EAGAIN) md_fuse_session_abort(v->session,error);
}
void md_fuse_file_release_handle(struct fuse_view *v,struct fuse_node *node,const struct md_identity *identity,
        uint64_t handle,unsigned flags) {
    md_fuse_file_release_remote(v,node->id,S_ISDIR(node->type),identity,handle,flags);
}
static void file_release(void *context) {
    struct fuse_file *f=context;
    if (atomic_fetch_sub_explicit(&f->references,1,memory_order_acq_rel)!=1) return;
    if (f->published) md_fuse_file_event(f->view,f->node,f->name,
        (f->flags&O_ACCMODE)==O_RDONLY ? IN_CLOSE_NOWRITE : IN_CLOSE_WRITE);
    md_fuse_node_closed(f->view,f->node);
    if (f->opened) md_fuse_file_release_handle(f->view,f->node,&f->opener,f->handle,f->flags);
    md_fuse_name_release(f->view,f->name);
    md_fs_anchor_release(f->origin); md_identity_release(&f->opener); md_fuse_view_release(f->view); free(f);
}
static int file_stat(void *context,struct stat *out) {
    struct fuse_file *f=context;
    pthread_mutex_lock(&f->view->lock); *out=f->node->attributes; pthread_mutex_unlock(&f->view->lock); return 0;
}
static int file_access(void *context,const struct md_identity *id,unsigned mode) {
    (void)context; (void)id; return mode&~7U ? -EINVAL : 0;
}
static int file_descriptor(void *context,int flags) {
    struct fuse_file *f=context;
    return md_fuse_node_open(f->view,f->node,(flags&(O_PATH|O_DIRECTORY))|O_RDONLY);
}
static struct md_fs_anchor *file_origin(void *context) { return ((struct fuse_file *)context)->origin; }
static size_t file_read_size(void *context) {
    struct fuse_file *f=context;
    return S_ISREG(f->node->type) ? md_fuse_session_read_size(f->view->session) : 0;
}
struct file_io {
    struct md_control_pending base;
    struct fuse_file *file;
    struct md_fuse_request *request;
    unsigned operation,command;
    size_t length;
    int64_t offset;
    struct md_resource_order turn;
    struct md_identity identity;
    struct md_resource_wakeup *wakeup;
    uint32_t pid;
    unsigned flags;
    int append_query,write_sent;
    int read_query;
    int command_sent,completed;
    int flush;
    unsigned char bytes[];
};
static int read_submit(struct file_io *io,const struct md_identity *identity,uint32_t pid,
        struct md_resource_wakeup *wakeup) {
    struct fuse_file *f=io->file;
    struct fuse_read_in in={.fh=f->handle,.offset=io->offset,.size=io->length,.flags=io->flags};
    unsigned opcode=io->operation==MD_CONTROL_GETDENTS ? FUSE_READDIR : FUSE_READ;
    if (opcode==FUSE_READDIR) in.size=md_fuse_session_read_size(f->view->session);
    return md_fuse_session_request(f->view->session,identity,pid,opcode,f->node->id,
        &in,sizeof(in),in.size,0,wakeup,&io->request);
}
static long write_advance(struct file_io *io) {
    struct fuse_file *f=io->file;
    if (!io->request) {
        int error=md_fuse_node_write_begin(f->view,f->node,&io->turn,io->wakeup);
        if (error) return error;
        if (io->command==MD_CONTROL_TRUNCATE) {
            struct fuse_setattr_in in={.valid=FATTR_SIZE|FATTR_FH,.fh=f->handle,.size=io->offset};
            error=md_fuse_session_request(f->view->session,&io->identity,io->pid,FUSE_SETATTR,
                f->node->id,&in,sizeof(in),sizeof(struct fuse_attr_out),0,io->wakeup,&io->request);
            if (!error) io->command_sent=1;
            return error;
        }
        if (io->flags&O_APPEND) {
            struct fuse_getattr_in in={.getattr_flags=FUSE_GETATTR_FH,.fh=f->handle};
            error=md_fuse_session_request(f->view->session,&io->identity,io->pid,FUSE_GETATTR,
                f->node->id,&in,sizeof(in),sizeof(struct fuse_attr_out),0,io->wakeup,&io->request);
            if (error) return error;
            io->append_query=1;
        }
    }
    if (io->append_query) {
        struct fuse_attr_out reply;
        long result=md_fuse_request_result(io->request,&reply,sizeof(reply));
        if (result<0) return result;
        struct stat st;
        int error=result!=sizeof(reply) ? -EPROTO : md_fuse_view_attributes(f->view,&reply.attr,&st);
        if (error) return error;
        io->offset=st.st_size;
        md_fuse_request_release(io->request); io->request=NULL; io->append_query=0;
    }
    if (!io->write_sent) {
        if (io->offset<0 || io->length>(uint64_t)(INT64_MAX-io->offset)) return -EFBIG;
        struct fuse_write_in in={.fh=f->handle,.offset=io->offset,.size=io->length,.flags=io->flags};
        memcpy(io->bytes,&in,sizeof(in));
        int error=md_fuse_session_request(f->view->session,&io->identity,io->pid,FUSE_WRITE,
            f->node->id,io->bytes,sizeof(in)+io->length,sizeof(struct fuse_write_out),0,
            io->wakeup,&io->request);
        if (error) return error;
        io->write_sent=1;
    }
    return 0;
}
static long io_resume(struct md_control_pending *base,void *data,size_t capacity) {
    struct file_io *io=(void *)base;
    if (io->read_query) {
        struct fuse_attr_out reply; struct stat st;
        long n=md_fuse_request_result(io->request,&reply,sizeof(reply));
        if (n<0) return n;
        int error=n!=sizeof(reply) ? -EPROTO : md_fuse_view_attributes(io->file->view,&reply.attr,&st);
        if (error) return error;
        if (io->offset>=st.st_size) return 0;
        if (io->length>(uint64_t)(st.st_size-io->offset)) io->length=(size_t)(st.st_size-io->offset);
        md_fuse_request_release(io->request); io->request=NULL; io->read_query=0;
        error=read_submit(io,&io->identity,io->pid,io->wakeup);
        if (error) return error;
    }
    if (io->flush) {
        long n=md_fuse_request_result(io->request,NULL,0);
        if (n!=-EINPROGRESS) io->completed=1;
        if (n==-ENOSYS) atomic_store_explicit(&io->file->view->no_flush,1,memory_order_relaxed);
        return n==-ENOSYS ? 0 : n;
    }
    if (io->operation==MD_CONTROL_WRITE || (io->command==MD_CONTROL_TRUNCATE && !io->command_sent)) {
        long result=write_advance(io);
        if (result) return result;
    }
    if (io->command) {
        struct fuse_attr_out result; struct stat st;
        long n=md_fuse_request_result(io->request,&result,sizeof(result));
        if (n!=-EINPROGRESS) io->completed=1;
        if (io->command!=MD_CONTROL_TRUNCATE) return n==-ENOSYS ? 0 : n>0 ? -EPROTO : n;
        if (n<0) return n;
        int error=n!=sizeof(result) ? -EPROTO : md_fuse_view_attributes(io->file->view,&result.attr,&st);
        if (error) return error;
        pthread_mutex_lock(&io->file->view->lock);
        io->file->node->attributes=st;
        io->file->node->attributes_until=0;
        ++io->file->node->changes;
        error=md_fuse_mapping_resize(io->file->node->mapping,st.st_size);
        pthread_mutex_unlock(&io->file->view->lock);
        md_fuse_file_event(io->file->view,io->file->node,io->file->name,IN_MODIFY); return error;
    }
    if (!io->operation) {
        struct fuse_attr_out result; struct stat st;
        long n=md_fuse_request_result(io->request,&result,sizeof(result));
        if (n<0) return n;
        int error=n!=sizeof(result) ? -EPROTO : md_fuse_view_attributes(io->file->view,&result.attr,&st);
        if (error) return error;
        if (!S_ISREG(st.st_mode)) return -EINVAL;
        pthread_mutex_lock(&io->file->view->lock);
        io->file->node->attributes=st;
        io->file->node->attributes_until=0;
        pthread_mutex_unlock(&io->file->view->lock);
        int64_t offset;
        return __builtin_add_overflow(io->offset,st.st_size,&offset) || offset<0 ? -EINVAL : offset;
    }
    if (io->operation==MD_CONTROL_WRITE) {
        struct fuse_write_out result;
        long n=md_fuse_request_result(io->request,&result,sizeof(result));
        if (n!=-EINPROGRESS) io->completed=1;
        if (n<0) return n;
        if (n!=sizeof(result) || result.size>io->length) return -EPROTO;
        struct fuse_view *v=io->file->view;
        pthread_mutex_lock(&v->lock);
        if (result.size) ++io->file->node->changes;
        if (result.size && io->file->node->attributes.st_size<io->offset+(int64_t)result.size)
            io->file->node->attributes.st_size=io->offset+result.size;
        int error=result.size ? md_fuse_mapping_write(io->file->node->mapping,
            io->bytes+sizeof(struct fuse_write_in),result.size,io->offset) : 0;
        pthread_mutex_unlock(&v->lock);
        if (result.size) md_fuse_file_event(v,io->file->node,io->file->name,IN_MODIFY);
        io->base.position=io->offset+result.size; io->base.position_valid=1;
        return error ? error : (long)result.size;
    }
    if (io->operation==MD_CONTROL_READ) {
        return md_fuse_request_result(io->request,data,capacity);
    }
    union { uint64_t alignment; unsigned char bytes[MD_FUSE_IO_SIZE]; } input;
    long n=md_fuse_request_result(io->request,input.bytes,sizeof(input.bytes));
    if (n<0) return n;
    size_t used=0;
    io->base.position=io->offset;
    for (size_t at=0;at<(size_t)n;) {
        const struct fuse_dirent *e=(const void *)(input.bytes+at);
        size_t remaining=(size_t)n-at;
        if (remaining<offsetof(struct fuse_dirent,name) || !e->namelen || e->namelen>NAME_MAX
                || FUSE_DIRENT_SIZE(e)>remaining || e->off>INT64_MAX || e->type>15
                || memchr(e->name,0,e->namelen) || memchr(e->name,'/',e->namelen)) return -EPROTO;
        size_t size=md_directory_record_write((char *)data+used,capacity-used,
            e->name,e->namelen,e->ino,(int64_t)e->off,(uint8_t)e->type);
        if (!size) return used ? (long)used : -EINVAL;
        used+=size; io->base.position=e->off;
        at+=FUSE_DIRENT_SIZE(e);
    }
    return used;
}
static void io_free(struct file_io *io) {
    md_fuse_request_release(io->request);
    md_fuse_node_write_end(&io->turn);
    md_resource_wakeup_release(io->wakeup);
    md_identity_release(&io->identity); file_release(io->file); free(io);
}
static void io_abandoned(void *context,int error,const void *data,size_t size) {
    (void)error; (void)data; (void)size;
    struct file_io *io=context;
    /* An admitted write retains its inode turn until the daemon answers. */
    long result=io_resume(&io->base,NULL,0);
    if (io->base.retired) io->base.retired(&io->base,result);
    io_free(io);
}
static void io_destroy(struct md_control_pending *base) {
    struct file_io *io=(void *)base;
    if (io->flush && io->request && !io->completed) {
        md_fuse_request_retire(io->request,io_abandoned,io); return;
    }
    if ((io->write_sent || io->command_sent) && !io->completed) {
        md_fuse_request_abandon(io->request,io_abandoned,io); return;
    }
    md_fuse_request_cancel(io->request);
    if (io->base.retired) io->base.retired(&io->base,-ECANCELED);
    io_free(io);
}
static long file_flush(void *context,const struct md_control_actor *actor,struct md_control_pending **out) {
    struct fuse_file *f=context;
    if (!f->opened || S_ISDIR(f->node->type)
            || atomic_load_explicit(&f->view->no_flush,memory_order_relaxed)) return 0;
    if (!actor || !actor->identity) return -EINVAL;
    struct file_io *io=calloc(1,sizeof(*io)); if (!io) return -ENOMEM;
    io->base=(struct md_control_pending){.resume=io_resume,.dispose=io_destroy,.deadline=INT64_MAX};
    io->file=file_retain(f); io->flush=1;
    struct fuse_flush_in in={.fh=f->handle};
    int error=md_fuse_session_request(f->view->session,actor->identity,md_fuse_view_pid(f->view,actor->pid),
        FUSE_FLUSH,f->node->id,&in,sizeof(in),0,0,actor->wakeup,&io->request);
    if (error) { io_destroy(&io->base); return error; }
    *out=&io->base; return -EINPROGRESS;
}
static long file_seek(void *context,const struct md_control_actor *actor,int64_t offset,int whence,
        struct md_control_pending **out) {
    struct fuse_file *f=context;
    if (whence==SEEK_SET || whence==SEEK_CUR) return -ENOSYS;
    if (whence!=SEEK_END || S_ISDIR(f->node->type)) return -EINVAL;
    if (!actor || !actor->identity || !f->opened) return -EBADF;
    struct file_io *io=calloc(1,sizeof(*io)); if (!io) return -ENOMEM;
    io->base=(struct md_control_pending){.resume=io_resume,.dispose=io_destroy,.deadline=INT64_MAX};
    io->file=file_retain(f); io->offset=offset;
    struct fuse_getattr_in in={.getattr_flags=FUSE_GETATTR_FH,.fh=f->handle};
    int error=md_fuse_session_request(f->view->session,actor->identity,md_fuse_view_pid(f->view,actor->pid),
        FUSE_GETATTR,f->node->id,&in,sizeof(in),sizeof(struct fuse_attr_out),0,actor->wakeup,&io->request);
    if (error) { io_destroy(&io->base); return error; }
    *out=&io->base; return -EINPROGRESS;
}
static long file_command(void *context,const struct md_control_actor *actor,unsigned command,int64_t value,
        struct md_control_pending **out) {
    struct fuse_file *f=context;
    if (!actor || !actor->identity || !f->opened) return -EBADF;
    int directory=S_ISDIR(f->node->type);
    union { struct fuse_setattr_in truncate; struct fuse_fsync_in sync; } input={0};
    unsigned opcode; size_t size,capacity=0;
    if (command==MD_CONTROL_TRUNCATE) {
        if (directory || value<0 || (f->flags&O_ACCMODE)==O_RDONLY) return -EINVAL;
        if (md_fs_anchor_policy(f->origin)&MD_MOUNT_READONLY) return -EROFS;
        input.truncate=(struct fuse_setattr_in){.valid=FATTR_SIZE|FATTR_FH,.fh=f->handle,.size=value};
        opcode=FUSE_SETATTR; size=sizeof(input.truncate); capacity=sizeof(struct fuse_attr_out);
    } else if (command==MD_CONTROL_SYNC || command==MD_CONTROL_DATASYNC) {
        input.sync=(struct fuse_fsync_in){.fh=f->handle,.fsync_flags=command==MD_CONTROL_DATASYNC};
        opcode=directory ? FUSE_FSYNCDIR : FUSE_FSYNC; size=sizeof(input.sync);
    } else return -EINVAL;
    struct file_io *io=calloc(1,sizeof(*io)); if (!io) return -ENOMEM;
    io->base=(struct md_control_pending){.resume=io_resume,.dispose=io_destroy,.deadline=INT64_MAX};
    io->file=file_retain(f); io->command=command;
    if (command==MD_CONTROL_TRUNCATE) {
        io->base.abandon=io_destroy;
        io->identity=md_identity_copy(actor->identity); io->offset=value;
        io->pid=md_fuse_view_pid(f->view,actor->pid); io->wakeup=md_resource_wakeup_retain(actor->wakeup);
        int error=(int)write_advance(io);
        if (error && error!=-EINPROGRESS) { io_destroy(&io->base); return error; }
        *out=&io->base; return -EINPROGRESS;
    }
    int error=md_fuse_session_request(f->view->session,actor->identity,md_fuse_view_pid(f->view,actor->pid),
        opcode,f->node->id,&input,size,capacity,0,actor->wakeup,&io->request);
    if (error) { io_destroy(&io->base); return error; }
    *out=&io->base; return -EINPROGRESS;
}
static long file_io(void *context,const struct md_control_actor *actor,unsigned operation,
        void *data,size_t size,int64_t offset,struct md_control_pending **out) {
    struct fuse_file *f=context;
    if (!actor || !actor->identity || !f->opened) return -EBADF;
    if (!size) return operation==MD_CONTROL_GETDENTS ? -EINVAL : 0;
    if (offset<0 || size>(uint64_t)(INT64_MAX-offset)) return -EINVAL;
    uint32_t max=operation==MD_CONTROL_WRITE ? md_fuse_session_write_size(f->view->session)
        : md_fuse_session_read_size(f->view->session);
    if (size>max) size=max;
    struct file_io *io=calloc(1,sizeof(*io)+(operation==MD_CONTROL_WRITE ? sizeof(struct fuse_write_in)+size : 0));
    if (!io) return -ENOMEM;
    io->base=(struct md_control_pending){.resume=io_resume,.dispose=io_destroy,.deadline=INT64_MAX};
    io->file=file_retain(f); io->operation=operation; io->offset=offset;
    io->length=size;
    io->flags=(f->flags&~(O_APPEND|O_NONBLOCK|O_NOATIME))|(actor->status_flags&(O_APPEND|O_NONBLOCK|O_NOATIME));
    if (operation==MD_CONTROL_WRITE) {
        io->base.abandon=io_destroy;
        io->identity=md_identity_copy(actor->identity);
        io->pid=md_fuse_view_pid(f->view,actor->pid); io->wakeup=md_resource_wakeup_retain(actor->wakeup);
        memcpy(io->bytes+sizeof(struct fuse_write_in),data,size);
        int error=(int)write_advance(io);
        if (error && error!=-EINPROGRESS) { io_destroy(&io->base); return error; }
        *out=&io->base; return -EINPROGRESS;
    }
    if (operation==MD_CONTROL_READ && S_ISREG(f->node->type) && !(f->open_flags&FOPEN_DIRECT_IO)) {
        pthread_mutex_lock(&f->view->lock);
        io->read_query=offset>=f->node->attributes.st_size;
        pthread_mutex_unlock(&f->view->lock);
    }
    int error;
    if (io->read_query) {
        /* Stale size is only a hint to revalidate, never evidence of EOF.
         * Normal reads need no extra request; direct-IO daemons own their EOF. */
        io->identity=md_identity_copy(actor->identity); io->pid=md_fuse_view_pid(f->view,actor->pid);
        io->wakeup=md_resource_wakeup_retain(actor->wakeup);
        struct fuse_getattr_in in={.getattr_flags=FUSE_GETATTR_FH,.fh=f->handle};
        error=md_fuse_session_request(f->view->session,actor->identity,io->pid,FUSE_GETATTR,
            f->node->id,&in,sizeof(in),sizeof(struct fuse_attr_out),0,actor->wakeup,&io->request);
    } else error=read_submit(io,actor->identity,md_fuse_view_pid(f->view,actor->pid),actor->wakeup);
    if (error) { io_destroy(&io->base); return error; }
    *out=&io->base; return -EINPROGRESS;
}
struct file_mapping {
    struct md_control_pending base;
    struct fuse_file *file;
    struct md_identity identity;
    struct md_resource_wakeup *wake;
    struct md_fuse_request *attributes;
    struct fuse_attr attr;
    struct md_fuse_image *image;
    uint32_t pid;
    uint64_t changes;
    int shared;
};
static long mapping_publish(struct file_mapping *p,int fd) {
    struct fuse_file *f=p->file;
    pthread_mutex_lock(&f->view->lock);
    int result=p->changes!=f->node->changes ? -EAGAIN
        : p->shared ? md_fuse_mapping_publish(&f->node->mapping,f->view->root,fd) : fd;
    pthread_mutex_unlock(&f->view->lock);
    if (result!=fd) close(fd);
    return result;
}
static long mapping_resume(struct md_control_pending *base,void *data,size_t size) {
    (void)data; (void)size;
    struct file_mapping *p=(void *)base;
    struct fuse_file *f=p->file;
    if (p->attributes) {
        struct fuse_attr_out reply;
        long result=md_fuse_request_result(p->attributes,&reply,sizeof(reply));
        if (result<0) return result;
        if (result!=sizeof(reply)) return -EPROTO;
        md_fuse_request_release(p->attributes); p->attributes=NULL;
        p->attr=reply.attr;
        int fd=(f->open_flags&FOPEN_DIRECT_IO) ? -ENOENT
            : md_fuse_snapshot_get(f->view,f->node,&p->attr,p->changes);
        if (fd>=0) return mapping_publish(p,fd);
        if (fd!=-ENOENT) return fd;
        int error=md_fuse_image_new(f->view->session,&p->identity,p->pid,f->node->id,f->handle,&p->attr,&p->image);
        if (error) return error;
    }
    int fd=md_fuse_image_advance(p->image,p->wake);
    if (fd<0) return fd;
    int error=(f->open_flags&FOPEN_DIRECT_IO) ? 0
        : md_fuse_snapshot_store(f->view,f->node,&p->attr,p->changes,fd);
    if (error) { close(fd); return error; }
    return mapping_publish(p,fd);
}
static void mapping_destroy(struct md_control_pending *base) {
    struct file_mapping *p=(void *)base;
    md_fuse_request_cancel(p->attributes); md_fuse_request_release(p->attributes);
    md_fuse_image_destroy(p->image); md_resource_wakeup_release(p->wake);
    md_identity_release(&p->identity); file_release(p->file); free(p);
}
static long file_mapping(void *context,const struct md_control_actor *actor,unsigned protection,unsigned flags,
        struct md_control_pending **out) {
    struct fuse_file *f=context;
    if (!actor || !actor->identity || !f->opened) return -EBADF;
    unsigned type=flags&MAP_TYPE;
    if (type!=MAP_PRIVATE && type!=MAP_SHARED && type!=MAP_SHARED_VALIDATE) return -EOPNOTSUPP;
    if (type!=MAP_PRIVATE && (protection&PROT_WRITE)) return -EACCES;
    if (type!=MAP_PRIVATE && (f->open_flags&FOPEN_DIRECT_IO)) return -ENODEV;
    if (!S_ISREG(f->node->type)) return -ENODEV;
    if ((protection&PROT_EXEC) && (md_fs_anchor_policy(f->origin)&MD_MOUNT_NOEXEC)) return -EPERM;
    int error=md_fuse_session_access(f->view->session,actor->identity);
    if (error) return error;
    struct file_mapping *p=calloc(1,sizeof(*p)); if (!p) return -ENOMEM;
    p->base=(struct md_control_pending){.resume=mapping_resume,.dispose=mapping_destroy,
        .deadline=INT64_MAX,.retry_preparation=1};
    p->file=file_retain(f); p->identity=md_identity_copy(actor->identity);
    p->shared=type!=MAP_PRIVATE;
    pthread_mutex_lock(&f->view->lock); p->changes=f->node->changes; pthread_mutex_unlock(&f->view->lock);
    p->pid=md_fuse_view_pid(f->view,actor->pid); p->wake=md_resource_wakeup_retain(actor->wakeup);
    struct fuse_getattr_in in={.getattr_flags=FUSE_GETATTR_FH,.fh=f->handle};
    error=md_fuse_session_request(f->view->session,actor->identity,p->pid,FUSE_GETATTR,f->node->id,
        &in,sizeof(in),sizeof(struct fuse_attr_out),0,actor->wakeup,&p->attributes);
    if (error) { mapping_destroy(&p->base); return error; }
    *out=&p->base; return -EINPROGRESS;
}
static void file_read_commit(void *context,size_t copied) {
    struct fuse_file *f=context;
    if (copied) md_fuse_file_event(f->view,f->node,f->name,IN_ACCESS);
}
static const struct md_control_file_ops file_ops={.retain=file_retain,.release=file_release,.stat=file_stat,
    .access=file_access,.descriptor=file_descriptor,.origin=file_origin,.filesystem_paths=1,
    .io=file_io,.read_size=file_read_size,.seek=file_seek,.command=file_command,.flush=file_flush,.mapping=file_mapping,.read_commit=file_read_commit,
    .splice=1,.mapping_readonly=1,.filesystem=FUSE_SUPER_MAGIC};

int md_fuse_file_publish(struct fuse_view *view,struct fuse_node *node,struct md_fs_anchor *origin,
        struct fuse_node *parent,const char *name,const struct md_identity *identity,unsigned flags,const struct fuse_open_out *opened,
        struct md_fs_result *out,const struct md_fs_output *output) {
    struct fuse_file *f=calloc(1,sizeof(*f)); if (!f) {
        if (opened) md_fuse_file_release_handle(view,node,identity,opened->fh,flags);
        return -ENOMEM;
    }
    atomic_init(&f->references,1); f->view=md_fuse_view_retain(view); f->node=node;
    f->name=md_fuse_name_retain(view,node,parent,name);
    f->origin=md_fs_anchor_retain(origin); f->opener=md_identity_copy(identity);
    f->flags=flags&~(O_CREAT|O_EXCL|O_TRUNC|O_NOCTTY);
    pthread_mutex_lock(&view->lock); ++node->opened; pthread_mutex_unlock(&view->lock);
    if (opened) {
        f->handle=opened->fh; f->open_flags=opened->open_flags; f->opened=1;
        md_fuse_snapshot_open(view,node,opened->open_flags);
    }
    int error=md_fs_control_publish(file_descriptor(f,f->flags),(struct md_control_file){&file_ops,f},
        f->origin,&f->opener,f->flags,out,output);
    if (!error && opened) { f->published=1; md_fuse_file_event(view,node,f->name,IN_OPEN); }
    file_release(f); return error;
}
