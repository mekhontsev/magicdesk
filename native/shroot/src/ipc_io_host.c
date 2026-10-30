#define _GNU_SOURCE
#include "ipc_io_host.h"
#include "socket_control.h"
#include "event_wait.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <sys/syscall.h>
#include <unistd.h>

struct md_ipc_io_entry {
    char *data, *control;
    size_t capacity, control_capacity;
    struct iovec vector;
    struct sockaddr_storage address;
};
struct md_ipc_io_batch {
    unsigned count, prepared, completed;
    int timed, received;
    struct timespec timeout;
    struct mmsghdr *messages;
    struct md_ipc_io_entry *entries;
    char *storage;
};
static void close_rights(struct msghdr *m) {
    for (struct cmsghdr *h=CMSG_FIRSTHDR(m);h;h=CMSG_NXTHDR(m,h)) {
        unsigned count=md_socket_control_fds(h);
        int *fds=(void *)CMSG_DATA(h);
        for (unsigned i=0;i<count;++i) close(fds[i]);
    }
}
void md_ipc_io_release(struct md_ipc_io *op) {
    if (op->job.started) md_ipc_io_interrupt(op);
    if (op->batch) {
        struct md_ipc_io_batch *batch=op->batch;
        for (unsigned i=0;i<batch->count;++i) {
            if (!batch->received && batch->messages[i].msg_len!=UINT_MAX)
                close_rights(&batch->messages[i].msg_hdr);
        }
        free(batch->storage); free(batch);
    } else {
        if (op->native_ready && op->native_result>=0) close_rights(&op->message);
        free(op->data); free(op->control);
    }
    memset(op,0,sizeof(*op));
}
int md_ipc_io_event(const struct md_ipc_io *op, int socket) {
    return op->job.started ? md_ipc_io_job_event(&op->job) : socket;
}
void md_ipc_io_interrupt(struct md_ipc_io *op) {
    op->native_result=md_ipc_io_job_finish(&op->job,1); op->native_ready=1;
}
static void select_entry(struct md_ipc_io *op, unsigned index) {
    struct md_ipc_io_entry *entry=&op->batch->entries[index];
    op->data=entry->data; op->control=entry->control;
    op->capacity=entry->capacity; op->control_capacity=entry->control_capacity;
}
static int prepare_entry(struct md_ipc_io *op, struct md_ipc_io_message *io) {
    struct md_ipc_io_batch *batch=op->batch;
    if (io->batch_count!=batch->count || io->batch_index!=batch->prepared || batch->prepared>=batch->count)
        return -EINVAL;
    struct md_ipc_io_entry *entry=&batch->entries[batch->prepared];
    if (io->vector_error) {
        if (io->vector_error!=-EFAULT && io->vector_error!=-EMSGSIZE && io->vector_error!=-EINVAL) return -EINVAL;
        /* Failed input snapshots retain the native batch validation boundary.
         * The kernel, not an adapter errno cache, owns the deferred error. */
        batch->messages[batch->prepared++].msg_hdr=(struct msghdr){
            .msg_iov=NULL,.msg_iovlen=io->vector_error==-EFAULT ? 1 : 1025,
            .msg_name=io->vector_error==-EINVAL ? &entry->address : NULL,
            .msg_namelen=io->vector_error==-EINVAL ? (socklen_t)-1 : 0};
        return 0;
    }
    entry->capacity=io->size; entry->control_capacity=io->control_size;
    entry->vector=(struct iovec){NULL,entry->capacity};
    batch->messages[batch->prepared].msg_hdr=(struct msghdr){.msg_iov=&entry->vector,.msg_iovlen=1,
        .msg_control=entry->control,.msg_controllen=entry->control_capacity,
        .msg_name=io->address_size ? &entry->address : NULL,.msg_namelen=io->address_size};
    select_entry(op,batch->prepared++); return 0;
}
static int allocate_batch(struct md_ipc_io_batch *batch) {
    size_t total=0;
    for (unsigned i=0;i<batch->prepared;++i) {
        const struct md_ipc_io_entry *entry=&batch->entries[i];
        size_t bytes=CMSG_ALIGN(entry->capacity)+CMSG_ALIGN(entry->control_capacity);
        if (bytes>SIZE_MAX-total) return -ENOMEM;
        total+=bytes;
    }
    if (!total) return 0;
    batch->storage=malloc(total);
    if (!batch->storage) return -ENOMEM;
    size_t offset=0;
    for (unsigned i=0;i<batch->prepared;++i) {
        struct md_ipc_io_entry *entry=&batch->entries[i];
        entry->data=entry->capacity ? batch->storage+offset : NULL;
        offset+=CMSG_ALIGN(entry->capacity);
        entry->control=entry->control_capacity ? batch->storage+offset : NULL;
        offset+=CMSG_ALIGN(entry->control_capacity);
        entry->vector.iov_base=entry->data;
        batch->messages[i].msg_hdr.msg_control=entry->control;
    }
    return 0;
}
int md_ipc_io_select(struct md_ipc_io *op, struct md_ipc_io_message *io, unsigned index) {
    struct md_ipc_io_batch *batch=op->batch;
    if (!batch || !batch->received || index>=batch->completed) return -EINVAL;
    select_entry(op,index);
    const struct msghdr *m=&batch->messages[index].msg_hdr;
    io->size=op->capacity; io->control_size=m->msg_controllen;
    io->address_size=m->msg_namelen; io->flags=m->msg_flags;
    io->address=batch->entries[index].address;
    io->timeout=batch->timeout;
    return (int)batch->messages[index].msg_len;
}
int md_ipc_io_prepare(struct md_ipc_io *op, struct md_ipc_io_message *io, int fd, int flags, int sending) {
    if (io->size>INT_MAX || io->control_size>INT_MAX
            || io->address_size>sizeof(io->address)) return -EINVAL;
    if (op->initialized) return op->batch ? prepare_entry(op,io) : -EINVAL;
    if (io->batch_count>MD_IPC_IO_BATCH || (io->batch_count && (io->batch_index || sending))) return -EINVAL;
    int type, mode=fcntl(fd,F_GETFL); socklen_t length=sizeof(type);
    if (mode<0 || getsockopt(fd,SOL_SOCKET,SO_TYPE,&type,&length)) return -errno;
    struct timeval timeout={0}; length=sizeof(timeout);
    if (getsockopt(fd,SOL_SOCKET,sending ? SO_SNDTIMEO : SO_RCVTIMEO,&timeout,&length)) return -errno;
    op->capacity=io->size; op->control_capacity=io->control_size;
    if (!io->batch_count && io->size>sizeof(io->data) && !(op->data=malloc(io->size))) return -ENOMEM;
    if (!io->batch_count && io->control_size>sizeof(io->control) && !(op->control=malloc(io->control_size))) {
        md_ipc_io_release(op); return -ENOMEM;
    }
    op->initialized=1; op->sending=sending; op->stream=type==SOCK_STREAM;
    /* The error queue is a snapshot, even on an otherwise blocking socket. */
    op->nonblocking=!!((mode&O_NONBLOCK) || (flags&MSG_DONTWAIT) || (!sending && (flags&MSG_ERRQUEUE)));
    if (!sending && op->stream && !op->nonblocking) {
        int low=1; length=sizeof(low);
        if (getsockopt(fd,SOL_SOCKET,SO_RCVLOWAT,&low,&length)) {
            int error=-errno; md_ipc_io_release(op); return error;
        }
        op->native_wait=!!(flags&MSG_WAITALL) || low>1;
    }
    op->deadline=INT64_MAX;
    if (timeout.tv_sec || timeout.tv_usec) {
        int64_t now=md_event_now();
        if (now<0) { md_ipc_io_release(op); return -errno; }
        if (timeout.tv_sec<(INT64_MAX-now)/1000000000LL)
            op->deadline=now+timeout.tv_sec*1000000000LL+timeout.tv_usec*1000;
    }
    if (io->batch_count) {
        unsigned count=io->batch_count;
        struct md_ipc_io_batch *batch=calloc(1,sizeof(*batch)+count*(sizeof(*batch->entries)+sizeof(*batch->messages)));
        if (!batch) { md_ipc_io_release(op); return -ENOMEM; }
        batch->count=count; batch->messages=(void *)(batch+1);
        batch->entries=(void *)(batch->messages+count);
        batch->timed=io->timed; batch->timeout=io->timeout;
        for (unsigned i=0;i<count;++i) batch->messages[i].msg_len=UINT_MAX;
        op->batch=batch; op->native_wait=1;
        return prepare_entry(op,io);
    }
    return 0;
}
int md_ipc_io_prepare_batch(struct md_ipc_io *op, struct md_ipc_io_message *io, int fd, int flags, unsigned count) {
    if (op->initialized || !count || count>io->batch_count || io->batch_count>MD_IPC_IO_BATCH)
        return -EINVAL;
    for (unsigned i=0;i<count;++i) {
        struct md_ipc_io_layout layout=io->layouts[i];
        if (layout.vector_error && i+1!=count) return -EINVAL;
        io->batch_index=i; io->size=layout.size; io->control_size=layout.control_size;
        io->address_size=layout.address_size; io->vector_error=layout.vector_error;
        int error=md_ipc_io_prepare(op,io,fd,flags,0);
        if (error) return error;
    }
    return 0;
}
int md_ipc_io_copy(struct md_ipc_io *op, struct md_ipc_io_message *io,
        size_t offset, size_t length, int control, int reading) {
    char *large=control ? op->control : op->data;
    char *small=control ? io->control : io->data;
    size_t capacity=control ? op->control_capacity : op->capacity;
    size_t chunk=control ? sizeof(io->control) : sizeof(io->data);
    if (!op->initialized || !large || reading==op->sending || length>chunk
            || offset>capacity || length>capacity-offset) return -EINVAL;
    if (reading) memcpy(small,large+offset,length);
    else memcpy(large+offset,small,length);
    return 0;
}
static int waiting(struct md_ipc_io *op, int error) {
    return (error==EAGAIN || error==EWOULDBLOCK) && !op->nonblocking
        && (op->deadline==INT64_MAX || md_event_now()<op->deadline);
}
static int admit(struct md_ipc_io *op,struct md_ipc_io_message *io,int fd,int flags,int sending) {
    if (op->initialized) return 0;
    if (!md_ipc_io_inline(io)) return -EINVAL;
    return md_ipc_io_prepare(op,io,fd,flags,sending);
}
static int collect_rights(struct md_received_fds *fds, struct msghdr *message) {
    int error=0;
    for (struct cmsghdr *h=CMSG_FIRSTHDR(message);h;h=CMSG_NXTHDR(message,h)) {
        unsigned count=md_socket_control_fds(h);
        int *received=(void *)CMSG_DATA(h);
        for (unsigned i=0;i<count;++i) {
            if (fds->count==fds->capacity) { close(received[i]); error=-EPROTO; continue; }
            fds->fds[fds->count]=received[i]; received[i]=(int)fds->count++;
        }
    }
    return error;
}
static int receive_batch(struct md_ipc_io *op, struct md_received_fds **custody,
        struct md_ipc_io_message *io, int flags, long *result) {
    struct md_ipc_io_batch *batch=op->batch;
    io->batch_count=0;
    if (!batch->prepared || batch->received) { *result=-EINVAL; return 0; }
    if (!op->job.started && !op->native_ready) {
        int error=allocate_batch(batch);
        if (!error) error=md_received_fds_reserve(custody,1+MD_SOCKET_RECEIVED_FDS*batch->prepared);
        if (error) { *result=error; return 0; }
        long args[5]={(*custody)->fds[0],(long)batch->messages,batch->prepared,
            flags|MSG_CMSG_CLOEXEC,batch->timed ? (long)&batch->timeout : 0};
        if (op->nonblocking || (flags&MSG_WAITFORONE)) {
            long value=syscall(SYS_recvmmsg,args[0],args[1],args[2],args[3]|MSG_DONTWAIT,args[4]);
            op->native_result=value<0 ? -errno : value;
            op->native_ready=op->nonblocking || op->native_result!=-EAGAIN;
        }
        if (!op->native_ready) {
            error=md_ipc_io_job_start(&op->job,SYS_recvmmsg,args);
            if (error) { *result=error; return 0; }
            return 1;
        }
    }
    *result=op->native_ready ? op->native_result : md_ipc_io_job_finish(&op->job,0);
    op->native_ready=0;
    int error=0;
    while (batch->completed<batch->count && batch->messages[batch->completed].msg_len!=UINT_MAX) {
        struct msghdr *message=&batch->messages[batch->completed++].msg_hdr;
        message->msg_flags&=~(MSG_CMSG_CLOEXEC&~flags);
        int collected=collect_rights(*custody,message);
        if (collected) error=collected;
    }
    batch->received=1; io->batch_count=batch->completed; io->timeout=batch->timeout;
    if (error) *result=error;
    return 0;
}
int md_ipc_io_receive(struct md_ipc_io *op, struct md_received_fds **custody,
        struct md_ipc_io_message *io, int flags, long *result) {
    if (!*custody || (*custody)->count!=1) { *result=-EINVAL; return 0; }
    int error=admit(op,io,(*custody)->fds[0],flags,0);
    if (error) { *result=error; return 0; }
    if (op->batch) return receive_batch(op,custody,io,flags,result);
    error=md_received_fds_reserve(custody,1+MD_SOCKET_RECEIVED_FDS);
    if (error) { *result=error; return 0; }
    struct md_received_fds *fds=*custody;
    char *bytes=op->data ? op->data : io->data;
    char *control=op->control ? op->control : io->control;
    struct iovec data={bytes+op->done,op->capacity-op->done};
    struct msghdr message={.msg_iov=&data,.msg_iovlen=1,
        .msg_control=control,.msg_controllen=op->control_capacity,
        .msg_name=io->address_size ? &io->address : NULL,.msg_namelen=io->address_size};
    ssize_t n;
    if (op->native_wait) {
        if (!op->job.started && !op->native_ready) {
            op->vector=data; op->message=message; op->message.msg_iov=&op->vector;
            long args[5]={fds->fds[0],(long)&op->message,flags|MSG_CMSG_CLOEXEC,0,0};
            error=md_ipc_io_job_start(&op->job,SYS_recvmsg,args);
            if (error) { *result=error; return 0; }
            return 1;
        }
        long returned=op->native_ready ? op->native_result : md_ipc_io_job_finish(&op->job,0);
        op->native_ready=0; message=op->message;
        if (returned<0) { *result=returned; return 0; }
        n=returned;
    } else n=recvmsg(fds->fds[0],&message,flags|MSG_DONTWAIT|MSG_CMSG_CLOEXEC);
    if (n<0) {
        error=errno; *result=op->done ? (long)op->done : -error;
        return waiting(op,error);
    }
    error=collect_rights(fds,&message);
    op->done+=(size_t)n;
    io->control_size=message.msg_controllen; io->address_size=message.msg_namelen;
    io->flags=message.msg_flags&~(MSG_CMSG_CLOEXEC&~flags);
    *result=error ? error : (long)op->done;
    return 0;
}
int md_ipc_io_send(struct md_ipc_io *op, const struct md_received_fds *fds,
        struct md_ipc_io_message *io, int flags, long *result) {
    if (!fds || !fds->count) { *result=-EINVAL; return 0; }
    int error=admit(op,io,fds->fds[0],flags,1);
    if (error) { *result=error; return 0; }
    char *bytes=op->data ? op->data : io->data;
    char *control=op->control ? op->control : io->control;
    struct iovec data={bytes+op->done,op->capacity-op->done};
    struct msghdr message={.msg_iov=&data,.msg_iovlen=1,
        .msg_control=op->done ? NULL : control,.msg_controllen=op->done ? 0 : op->control_capacity,
        .msg_name=io->address_size ? &io->address : NULL,.msg_namelen=io->address_size};
    unsigned cursor=1;
    for (struct cmsghdr *h=CMSG_FIRSTHDR(&message);h;h=CMSG_NXTHDR(&message,h)) {
        size_t offset=(char *)h-control;
        if (h->cmsg_len<CMSG_LEN(0) || offset>op->control_capacity
                || h->cmsg_len>op->control_capacity-offset) { *result=-EINVAL; return 0; }
        if (h->cmsg_level==SOL_SOCKET && h->cmsg_type==SCM_RIGHTS) {
            unsigned count=(h->cmsg_len-CMSG_LEN(0))/sizeof(int);
            if (count>fds->count-cursor) { *result=-EINVAL; return 0; }
            memcpy(CMSG_DATA(h),fds->fds+cursor,count*sizeof(int)); cursor+=count;
        } else if (h->cmsg_level==SOL_SOCKET && h->cmsg_type==SCM_CREDENTIALS
                && h->cmsg_len==CMSG_LEN(sizeof(struct ucred))) {
            struct ucred native={getpid(),getuid(),getgid()};
            memcpy(CMSG_DATA(h),&native,sizeof(native));
        }
    }
    if (!op->done && cursor!=fds->count) { *result=-EPROTO; return 0; }
    ssize_t n=sendmsg(fds->fds[0],&message,flags|MSG_DONTWAIT|MSG_NOSIGNAL);
    if (n<0) { int error=errno; *result=op->done ? (long)op->done : -error; return waiting(op,error); }
    op->done+=(size_t)n; *result=(long)op->done;
    return n>0 && op->stream && op->done<op->capacity && waiting(op,EAGAIN);
}
