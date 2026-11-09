#include "fs_buffer.h"
#include "adapter_buffer.h"
#include "fs_wire.h"
#include "interception.h"
#include "fd_filter.h"
#include "namespace_internal.h"
#include "raw.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <sys/resource.h>

extern long md_guest_buffer_control(unsigned, unsigned long, unsigned long);
extern long md_guest_open_anchor(int, const char *, int, unsigned, int);
static long open_anchor(void *context,const char *path,int flags) {
    (void)context;
    return md_guest_open_anchor(AT_FDCWD,path,flags,0,MD_FS_BUFFER_SOURCE);
}
int md_fs_buffer_accepts(const struct md_fs_request *q) {
    return md_adapter_buffer_ready() && q && md_fs_operation_valid(q->operation);
}
static long check_channels(struct md_fs_buffer_frame *frame, long result) {
    /* EVENT_WAIT: each reply selects one ambiguous slot under the table turn;
     * cancellation keeps that slot uncertain, never admits another channel. */
    while (result==MD_FS_BUFFER_CHANNEL_CHECK) {
        int fd=frame->channel.number;
        struct md_fd_channel_observation *o=&frame->channel.observation;
        *o=(struct md_fd_channel_observation){.length=sizeof(o->cookie)};
        long flags=RAW3(fcntl,fd,F_GETFD,0);
        o->error=flags<0 ? (int)flags : (int)RAW5(getsockopt,fd,SOL_SOCKET,SO_COOKIE,&o->cookie,&o->length);
        if (!o->error) o->flags=(unsigned)flags;
        result=md_guest_buffer_control(MD_FS_BUFFER_CHANNEL_OBSERVED,0,0);
    }
    return result;
}
long md_adapter_receive_fd(struct md_fs_buffer_frame *frame, unsigned flags, unsigned begin) {
    struct md_fd_receipt *receipt=(void *)frame->receipt;
    int error=md_fd_receipt_reset(receipt,1);
    if (error) return error;
    frame->receive.token=0; frame->receive.number=-1;
    receipt->vector=(struct iovec){&frame->receive.token,sizeof(frame->receive.token)};
    long result=check_channels(frame,md_guest_buffer_control(begin,0,0));
    if (!result && frame->receive.number<0) {
        result=md_guest_buffer_admit(MD_FS_BUFFER_IMPORT);
        if (result>=0) result=md_guest_buffer_control(MD_FS_BUFFER_RIGHTS_READY,0,0);
    }
    if (!result) result=RAW3(recvmsg,frame->receive.number,&receipt->message,
        MSG_DONTWAIT|((flags&O_CLOEXEC) ? MSG_CMSG_CLOEXEC : 0));
    return md_guest_buffer_control(MD_FS_BUFFER_RIGHTS_DONE,(unsigned long)result,0);
}
static long receive_result(struct md_fs_buffer_frame *frame, const struct md_fs_request *q,
        struct md_fs_response *out, int (*deliver)(void *, const void *, size_t), void *context) {
    long result=0;
    out->delivery=frame->reply.delivery;
    out->result=frame->reply.result;
    const void *data=md_fs_operation_value(q->operation) ? (const void *)frame->value : frame->reply.data;
    size_t capacity=md_fs_operation_value(q->operation) ? sizeof(frame->value) : sizeof(out->data);
    if (out->result.size<=sizeof(out->data))
        memcpy(out->data,data,out->result.size);
    unsigned transfer=frame->transfer;
    unsigned descriptors=transfer!=MD_FS_BUFFER_NO_FD;
    if (out->delivery!=MD_FS_REPLIED || out->result.fd!=-1 || out->result.open_completion.kind
            || transfer>MD_FS_BUFFER_REPLAY_ADDFD
            || !md_fs_result_valid(q,&out->result,data,capacity,descriptors)) result=-EPROTO;
    else if (transfer==MD_FS_BUFFER_TASK_PATH) {
        if (q->operation!=MD_FS_TASK_PATH || frame->source[sizeof(frame->source)-1]
                || !md_prefix(frame->source,"/proc/")) result=-EPROTO;
        else if (q->mode==MD_FS_TASK_CHDIR) out->result.error=(int)RAW1(chdir,frame->source);
        else if (q->mode==MD_FS_TASK_FCHDIR) {
            long changed=RAW1(chdir,frame->source);
            long completed=md_guest_buffer_control(MD_FS_BUFFER_TASK_DONE,(unsigned long)changed,0);
            out->result.error=(int)(completed ? completed : changed);
        }
        else if (q->mode==MD_FS_TASK_TRUNCATE) out->result.error=(int)RAW2(truncate,frame->source,q->offset);
        else result=-EPROTO;
    }
    else if (transfer==MD_FS_BUFFER_ADDFD || transfer==MD_FS_BUFFER_REPLAY_ADDFD) {
        long fd=transfer==MD_FS_BUFFER_REPLAY_ADDFD
            ? md_guest_buffer_control(MD_FS_BUFFER_RESULT_REPLAY,0,0)
            : md_guest_buffer_admit(MD_FS_BUFFER_RESULT_ADMIT);
        if (fd<0) out->result.error=(int)fd;
        else out->result.fd=(int)fd;
    } else if (transfer==MD_FS_BUFFER_RIGHTS) {
        long fd=md_adapter_receive_fd(frame,q->flags,MD_FS_BUFFER_RIGHTS_BEGIN);
        if (fd<0) out->result.error=(int)fd;
        else out->result.fd=(int)fd;
    } else if (transfer==MD_FS_BUFFER_ANCHOR || transfer==MD_FS_BUFFER_CALLER_OPEN) {
        if (frame->source[sizeof(frame->source)-1] || !md_prefix(frame->source,"/proc/")) result=-EPROTO;
        else if (transfer==MD_FS_BUFFER_ANCHOR
                && (!(frame->source_flags&O_PATH)
                    || (frame->source_flags&~(O_PATH|O_CLOEXEC|O_DIRECTORY|O_NOFOLLOW)))) result=-EPROTO;
        else {
            long fd=md_open_pinned_path(frame->source,frame->source_flags,open_anchor,NULL);
            if (fd>=0 && frame->source_verify) {
                struct stat st;
                long checked=RAW2(fstat,fd,&st);
                if (!checked && !md_fs_buffer_source_matches(frame,st.st_dev,st.st_ino)) checked=-ESTALE;
                if (checked<0) { RAW1(close,fd); fd=checked; }
            }
            if (fd<0) out->result.error=(int)fd;
            else out->result.fd=(int)fd;
        }
    }
    if (!result && !out->result.error && out->result.fd>=0 && out->result.selectors) {
        result=md_fd_filter_arm_mask(out->result.selectors,out->result.fd);
        if (result) { RAW1(close,out->result.fd); out->result.fd=-1; }
    }
    if ((q->operation==MD_FS_WATCH_READ || q->operation==MD_FS_GETDENTS)
            && !out->result.error && !out->result.host_path) {
        int copied=result ? (int)result : deliver ? deliver(context,out->data,out->result.size) : 0;
        long committed=md_guest_buffer_control(MD_FS_BUFFER_ACK,(unsigned long)(long)copied,0);
        if (committed) out->delivery=MD_FS_UNCONFIRMED;
        result=copied ? copied : committed;
    }
    if (!result && !out->result.error && !out->result.host_path && md_fs_operation_value(q->operation)) {
        if (deliver) result=deliver(context,data,out->result.size);
        else if (out->result.size>sizeof(out->data)) result=-EOVERFLOW;
    }
    return result;
}
long md_guest_buffer_result(unsigned flags,unsigned mode,struct md_fs_buffer_frame *frame) {
    struct md_fs_request q={.operation=MD_FS_OPEN,.directory={-1,-1},.flags=flags,.mode=mode};
    struct md_fs_response out={0};
    long result=receive_result(frame,&q,&out,NULL,NULL);
    if (result) return result;
    if (out.result.error) return out.result.error;
    return md_namespace_open_result(&out.result,(int)flags);
}
static long exchange(const struct md_fs_request *q, struct md_fs_response *out,
        int (*deliver)(void *, const void *, size_t), void *context, unsigned command,
        struct md_command_recipe *recipe, unsigned long prepared) {
    long address=prepared ? (long)prepared : md_adapter_buffer_take();
    if (address<0) return address;
    struct md_fs_buffer_frame *frame=(void *)address;
    long result=0;
    frame->input.base[0]=q->directory[0]; frame->input.base[1]=q->directory[1];
    frame->input.flags=q->flags; frame->input.mode=q->mode; frame->input.capacity=q->capacity;
    frame->input.resolve=q->resolve; frame->input.offset=q->offset;
    frame->input.attributes=q->attributes;
    frame->input.value_size=q->value_size;
    if (q->value_size>MD_FS_VALUE_MAX) result=-EINVAL;
    else if (q->value_size) result=md_read_memory(frame->value,q->value,q->value_size);
    frame->input.length[0]=frame->input.length[1]=0;
    for (unsigned i=0;i<2 && !result;++i) {
        frame->input.capture_length[i]=0;
        frame->input.capture_flags[i]=q->capture[i].flags;
        if (q->capture[i].path) {
            size_t n=md_length(q->capture[i].path);
            if (n>=PATH_MAX) { result=-ENAMETOOLONG; break; }
            frame->input.capture_length[i]=n+1;
            memcpy(frame->input.capture_path[i],q->capture[i].path,n+1);
        }
        frame->input.object[i][0]=0;
        if (q->object[i]) {
            if (!md_fs_object_id_valid(q->object[i])) { result=-EINVAL; break; }
            memcpy(frame->input.object[i],q->object[i],33);
        }
        if (!q->path[i]) continue;
        size_t n=0;
        while (q->path[i][n] && n<PATH_MAX-1) ++n;
        if (q->path[i][n]) result=-ENAMETOOLONG;
        else {
            frame->input.length[i]=n+1;
            memcpy(frame->input.path[i],q->path[i],n+1);
        }
    }
    if (!result) result=check_channels(frame,md_guest_buffer_control(command,q->operation,0));
    if (result==MD_FS_BUFFER_EXPORT) {
        uint64_t request=frame->export.request;
        unsigned mask=frame->export.mask;
        long fd=frame->export.number;
        long sent=-EPROTO;
        if (request && mask && !(mask&~3U)) {
            if (fd<0) fd=md_guest_buffer_admit(MD_FS_BUFFER_EXPORT);
            struct md_fs_rights rights={0};
            for (unsigned i=0;i<2;++i)
                if (mask&(1U<<i)) rights.fd[rights.count++]=q->directory[i];
            sent=fd<0 ? fd : md_fs_send((int)fd,&request,sizeof(request),&rights);
            if (sent>=0) sent=sent==sizeof(request) ? 0 : -EIO;
        }
        result=md_guest_buffer_control(MD_FS_BUFFER_SENT,(unsigned long)sent,0);
    }
    if (!result && recipe) memcpy(recipe,&frame->command,sizeof(*recipe));
    if (!result && out) result=receive_result(frame,q,out,deliver,context);
    long released=prepared ? 0 : md_adapter_buffer_release();
    return result ? result : released;
}
long md_fs_buffer_proc_link(int process, void *out, size_t capacity) {
    if (process<=0 || !capacity) return -EINVAL;
    long address=md_adapter_buffer_take();
    if (address<0) return address;
    struct md_fs_buffer_frame *frame=(void *)address;
    long result=md_guest_buffer_control(MD_FS_BUFFER_PROC_LINK,(unsigned)process,0);
    if (result>=0) {
        if (result>=PATH_MAX || (size_t)result>=sizeof(frame->reply.data)) result=-EPROTO;
        else {
            size_t count=(size_t)result<capacity ? (size_t)result : capacity;
            long error=md_write_memory(out,frame->reply.data,count);
            result=error<0 ? error : (long)count;
        }
    }
    long released=md_adapter_buffer_release();
    return result<0 ? result : released ? released : result;
}
long md_fs_buffer_snapshot(unsigned flags, long (*produce)(void *, void *, size_t), void *context) {
    if (!produce) return -EINVAL;
    long address=md_adapter_buffer_take();
    if (address<0) return address;
    struct md_fs_buffer_frame *frame=(void *)address;
    long result=md_guest_buffer_control(MD_FS_BUFFER_SNAPSHOT_BEGIN,flags,0);
    while (!result) {
        long size=produce(context,frame->snapshot.data,sizeof(frame->snapshot.data));
        if (size<0) { result=size; break; }
        if (!size) break;
        if ((size_t)size>sizeof(frame->snapshot.data)) { result=-EOVERFLOW; break; }
        frame->snapshot.size=(size_t)size;
        result=md_guest_buffer_control(MD_FS_BUFFER_SNAPSHOT_APPEND,0,0);
    }
    struct md_fs_response out={0};
    if (!result) result=md_guest_buffer_control(MD_FS_BUFFER_SNAPSHOT_FINISH,0,0);
    if (!result) {
        struct md_fs_request q={.operation=MD_FS_TEMPORARY,.directory={-1,-1},.flags=flags};
        result=receive_result(frame,&q,&out,NULL,NULL);
        if (!result) result=out.result.error;
    }
    long released=md_adapter_buffer_release();
    return result ? result : released ? released : out.result.fd;
}
long md_fs_buffer_call(const struct md_fs_request *q, struct md_fs_response *out,
        int (*deliver)(void *, const void *, size_t), void *context) {
    if (!md_fs_buffer_accepts(q)) return -EINVAL;
    return exchange(q,out,deliver,context,MD_FS_BUFFER_CALL,NULL,0);
}
long md_fs_buffer_inspect(const int *indices, unsigned count, struct md_received_message *out) {
    if (!count || count>253) return -EINVAL;
    long address=md_adapter_buffer_take();
    if (address<0) return address;
    struct md_fs_buffer_frame *frame=(void *)address;
    frame->rights.count=count; memcpy(frame->rights.index,indices,count*sizeof(int));
    long result=md_guest_buffer_control(MD_FS_BUFFER_RECEIVED_INSPECT,0,0);
    if (!result) memcpy(out,&frame->received_message,sizeof(*out));
    long released=md_adapter_buffer_release();
    return result ? result : released;
}
static long publish_mapped(struct md_fs_buffer_frame *frame, unsigned count, const unsigned char *selectors,
        void *control, size_t capacity, unsigned flags, int *out_flags, size_t *used) {
    struct md_fd_receipt *receipt=(void *)frame->receipt;
    size_t fit=capacity>CMSG_LEN(0) ? (capacity-CMSG_LEN(0))/sizeof(int) : 0;
    unsigned writable=0;
    int numbers[MD_FD_RECEIPT_MAX];
    unsigned candidate=fit<count ? (unsigned)fit : count;
    void *words=(char *)control+CMSG_LEN(0);
    /* Match static partial-control faults without changing unused output. The
     * shared receipt, never preflight bytes, owns the installed prefix even if
     * another thread remaps the destination or the caller dies during copying. */
    if (!md_write_memory(words,words,candidate*sizeof(int))) writable=candidate;
    while (writable<candidate) {
        void *slot=(char *)control+CMSG_LEN(0)+writable*sizeof(int);
        if (md_write_memory(slot,slot,sizeof(int))) break;
        ++writable;
    }
    receipt->message.msg_controllen=writable ? CMSG_LEN(writable*sizeof(int)) : 0;
    long received=RAW3(recvmsg,frame->receive.number,&receipt->message,MSG_DONTWAIT|(flags&MSG_CMSG_CLOEXEC));
    if (received<0) return received;
    int delivered=md_fd_receipt_read(receipt,count,numbers);
    if (delivered<0) return delivered;
    long error=0;
    for (int i=0;i<delivered && !error;++i)
        if (selectors[i]) error=md_fd_filter_arm_mask(selectors[i],numbers[i]);
    *used=0;
    if (delivered && !error) {
        struct cmsghdr h={.cmsg_len=CMSG_LEN((unsigned)delivered*sizeof(int)),.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS};
        if (!md_write_memory((char *)control+CMSG_LEN(0),numbers,(unsigned)delivered*sizeof(int))
                && !md_write_memory((char *)control+offsetof(struct cmsghdr,cmsg_level),&h.cmsg_level,sizeof(h.cmsg_level))
                && !md_write_memory((char *)control+offsetof(struct cmsghdr,cmsg_type),&h.cmsg_type,sizeof(h.cmsg_type))
                && !md_write_memory(control,&h.cmsg_len,sizeof(h.cmsg_len))) {
            size_t n=CMSG_SPACE((unsigned)delivered*sizeof(int)); *used=n<capacity ? n : capacity;
        }
    }
    if ((unsigned)delivered<count) *out_flags|=MSG_CTRUNC;
    return error ? error : received;
}
long md_fs_buffer_publish(enum md_received_source source, const int *indices, const unsigned char *selectors, unsigned count, void *control, size_t capacity,
        unsigned flags, int *out_flags, size_t *used) {
    if (!count || count>253) return -EINVAL;
    int has_selectors=0;
    for (unsigned i=0;i<count;++i) has_selectors|=selectors[i];
    if (has_selectors) {
        long ready=md_fd_filter_prepared((unsigned)has_selectors);
        if (ready<0) return ready;
        if (ready) has_selectors=0;
    }
    long address=md_adapter_buffer_take();
    if (address<0) return address;
    struct md_fs_buffer_frame *frame=(void *)address;
    frame->rights.count=count; frame->rights.source=source;
    memcpy(frame->rights.index,indices,count*sizeof(int));
    frame->receive.token=0; frame->receive.number=-1; frame->receive.mapped=0;
    struct md_fd_receipt *receipt=(void *)frame->receipt;
    long initialized=md_fd_receipt_reset(receipt,count);
    if (initialized) { md_adapter_buffer_release(); return initialized; }
    receipt->vector=(struct iovec){&frame->receive.token,sizeof(frame->receive.token)};
    long result=check_channels(frame,md_guest_buffer_control(MD_FS_BUFFER_RECEIVED_RIGHTS,0,0));
    if (!result && frame->receive.number<0) {
        result=md_guest_buffer_admit(MD_FS_BUFFER_IMPORT);
        if (result>=0) result=md_guest_buffer_control(MD_FS_BUFFER_RIGHTS_READY,0,0);
    }
    /* Prepared sandbox filters need neither a limit query nor a free-slot
     * scan. Otherwise the table turn spans selector preparation and kernel
     * copy; only descriptors that can fit are armed, never speculative FDs. */
    if (!result && has_selectors) {
        struct rlimit limit;
        result=RAW4(prlimit64,0,RLIMIT_NOFILE,NULL,&limit);
        size_t fit=capacity>CMSG_LEN(0) ? (capacity-CMSG_LEN(0))/sizeof(int) : 0;
        unsigned next=0;
        for (unsigned fd=0;!result && next<count && next<fit && fd<limit.rlim_cur && fd<INT_MAX;++fd) {
            long occupied=RAW2(fcntl,fd,F_GETFD);
            if (occupied==-EBADF) {
                if (selectors[next]) result=md_fd_filter_arm_mask(selectors[next],(int)fd);
                ++next;
            } else if (occupied<0) result=occupied;
        }
    }
    struct iovec vector={&frame->receive.token,sizeof(frame->receive.token)};
    struct msghdr message={.msg_iov=&vector,.msg_iovlen=1,.msg_control=control,.msg_controllen=capacity};
    if (!result) result=frame->receive.mapped
        ? publish_mapped(frame,count,selectors,control,capacity,flags,out_flags,used)
        : RAW3(recvmsg,frame->receive.number,&message,MSG_DONTWAIT|(flags&MSG_CMSG_CLOEXEC));
    long finished=md_guest_buffer_control(MD_FS_BUFFER_RIGHTS_DONE,(unsigned long)result,0);
    if (result>=0 && !finished && !frame->receive.mapped) { *used=message.msg_controllen; *out_flags|=message.msg_flags&MSG_CTRUNC; }
    else if (result==-EMFILE) { *used=0; *out_flags|=MSG_CTRUNC; finished=0; }
    long released=md_adapter_buffer_release();
    return finished ? finished : released;
}
long md_fs_buffer_exec_prepare(const struct md_fs_request *q, struct md_command_recipe *recipe,
        unsigned long prepared) {
    return exchange(q,NULL,NULL,NULL,MD_FS_BUFFER_EXEC_PREPARE,recipe,prepared);
}
void md_fs_buffer_exec_cancel(void) {
    md_guest_buffer_control(MD_FS_BUFFER_EXEC_CANCEL,0,0);
}
