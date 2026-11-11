#define _GNU_SOURCE
#include <sys/un.h>
#include "task_helper.h"
#include "mapping_observation.h"
#include "fd_channel.h"
#include "received_fds.h"
#include "socket_control.h"
#include "proc_paths.h"
#include "signal_protocol.h"
#include "task_checkpoint.h"
#include "task_registers.h"
#include "task_wait.h"
#include "task_pending.h"
#include "task_stack.h"
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

enum operation { ALLOCATE, PROTECT_STACK, RELEASE_STACK, CLONE, CLEAR, CHANNEL, OPEN, METADATA, DIRECTORY, EXPORT, BACKING, MAP, EXIT,
    SEND_INPUT, SEND_CHANNEL, SEND_ANCHOR, SEND_IMPORT, SEND_CLOSE, SEND_SOCKET, SEND,
    RECEIVE_CHANNEL, RECEIVE, RECEIVE_EXPORT, RECEIVE_COLLECT };
struct parcel {
    uint64_t token;
    struct iovec vector;
    struct msghdr message;
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control;
    char path[PATH_MAX];
    struct stat metadata;
};
struct helper {
    struct helper *next;
    struct md_task_helpers *manager;
    struct md_task_wait_member wait_member;
    pid_t owner, group, child;
    int parent_ready, reaped, started, cancelled, before, observed, child_stopped;
    enum operation parent_operation, operation;
    long result;
    struct md_interception_abi abi;
    struct md_interception_memory *memory;
    struct md_interception_buffer buffer;
    int borrowed;
    int parent_shielded;
    uint64_t parent_mask;
    struct user_pt_regs parent_registers;
    struct md_task_call parent_call;
    uintptr_t stack_mapping;
    uintptr_t stack, parcel;
    uint64_t pstate;
    struct md_task_mapping_request request;
    enum md_task_helper_kind kind;
    int flags;
    int capture_metadata, captured_fd;
    void *directory;
    size_t directory_size, directory_limit;
    struct stat metadata;
    struct md_task_send send;
    struct md_received_fds *received;
    struct md_received_fds *send_inputs;
    uintptr_t send_import;
    uintptr_t send_address;
    int send_anchor;
    uint64_t anchor_token;
    uint64_t send_token;
    int export_fds[253], endpoint;
    unsigned keep_index, clear_from;
    siginfo_t signal;
    void (*captured)(void *);
    md_task_helper_complete complete;
    struct md_mapping_source source;
    struct md_fd_channel *channel;
    struct md_fd_channel_capture capture;
    int backing, maps;
    unsigned notifications;
    int notification_result;
    void *completion;
    unsigned export_count, export_offset;
    int export_inputs[];
};
static int io_helper(const struct helper *m) {
    return m->kind==MD_TASK_HELPER_RECEIVE
        || m->kind==MD_TASK_HELPER_EXPORT || m->kind==MD_TASK_HELPER_SEND_CAPTURED;
}
static int descriptor_helper(const struct helper *m) {
    return m->kind==MD_TASK_HELPER_CAPTURE || m->kind==MD_TASK_HELPER_FD_TABLE;
}
static struct md_task_receive_frame *receive_frame(struct helper *m) {
    struct md_buffer_span span;
    return md_buffer_span(&m->buffer,&span) ? NULL : span.data;
}
struct md_task_helpers {
    struct helper *first;
    struct md_task_helper_callbacks callbacks;
    unsigned count;
};
static long trace(struct helper *m, int request, pid_t pid, void *address, void *data) {
    long r=m->manager->callbacks.trace(request,pid,address,data);
    return r<0 ? -errno : r;
}
static int protocol_error(struct helper *m,int line) {
    fprintf(stderr,"guest-runtime: helper protocol line=%d owner=%d child=%d kind=%d parent=%d operation=%d ready=%d reaped=%d cancelled=%d started=%d\n",
        line,m->owner,m->child,m->kind,m->parent_operation,m->operation,
        m->parent_ready,m->reaped,m->cancelled,m->started);
    if (m->borrowed) fprintf(stderr,"guest-runtime: helper call stage=%u boundary=%d result=%ld syscall=%ld argument=%#lx original=%#llx\n",
        m->parent_call.stage,m->parent_call.boundary,m->parent_call.result,
        m->parent_call.arguments.number,m->parent_call.arguments.value[0],
        (unsigned long long)m->parent_call.original_first_argument);
    return -EPROTO;
}
static int resume(struct helper *m, pid_t pid) {
    long r=trace(m,PTRACE_CONT,pid,NULL,NULL);
    if (!r && pid==m->child) m->child_stopped=0;
    /* EVENT_WAIT: ESRCH can precede an already queued exec/exit. Retain every
     * resource until that exact event; the launch deadline bounds lost events. */
    return r==-ESRCH ? 0 : (int)r;
}
static int registers(struct helper *m, pid_t pid, struct user_pt_regs *r, int write) {
    struct iovec io={r,sizeof(*r)};
    return (int)trace(m,write ? PTRACE_SETREGSET : PTRACE_GETREGSET,pid,(void *)NT_PRSTATUS,&io);
}
static int call(struct helper *m, pid_t pid, long nr, const unsigned long args[6]) {
    struct user_pt_regs r=md_task_registers_entry(m->abi.task_call,m->stack,m->pstate);
    memcpy(r.regs,args,6*sizeof(*args)); r.regs[8]=nr;
    int error=registers(m,pid,&r,1);
    return error==-ESRCH ? 0 : error ? error : resume(m,pid);
}
static void dispose(struct helper *m) {
    struct md_task_helpers *manager=m->manager;
    md_task_wait_untrack(manager->callbacks.wait,&m->wait_member);
    struct helper **p=&manager->first;
    while (*p && *p!=m) p=&(*p)->next;
    if (*p) { *p=m->next; --manager->count; }
    md_fd_channel_capture_cancel(&m->capture);
    md_received_fds_release(m->received);
    md_received_fds_release(m->send_inputs);
    if (m->send_address && m->send_anchor>=0) close(m->send_anchor);
    md_fd_channel_release(m->channel);
    if (m->maps>=0) close(m->maps);
    if (m->backing>=0) close(m->backing);
    md_buffer_release(&m->buffer);
    if (m->memory) md_memory_stack_return(m->memory,m->stack);
    md_memory_release(m->memory);
    free(m->directory);
    free(m);
}
static int parent_restore(struct helper *m) {
    if (!m->parent_shielded || !m->owner) return 0;
    int error=registers(m,m->owner,&m->parent_registers,1);
    if (!error) error=(int)trace(m,PTRACE_SETSIGMASK,m->owner,
        (void *)sizeof(m->parent_mask),&m->parent_mask);
    if (!error || error==-ESRCH) m->parent_shielded=0;
    return error==-ESRCH ? 0 : error;
}
static int complete(struct helper *m) {
    int error=parent_restore(m);
    if (error) return error;
    void *context=m->completion;
    struct md_task_helper_result result={.value=m->result,.descriptor=-1,.signal=m->signal,.metadata=m->metadata};
    if (descriptor_helper(m) && !m->result && !m->cancelled) {
        result.descriptor=m->maps; m->maps=-1;
        result.directory=m->directory; m->directory=NULL;
        result.directory_size=m->directory_size;
    } else if (descriptor_helper(m) && m->cancelled) result.value=-ECANCELED;
    if (m->kind==MD_TASK_HELPER_RECEIVE || m->kind==MD_TASK_HELPER_EXPORT) {
        if (m->cancelled) result.value=-ECANCELED;
        else if (!m->result) { result.received=m->received; m->received=NULL; }
    }
    md_task_helper_complete callback=m->complete;
    dispose(m);
    callback(context,result);
    return 0;
}
static int abort_child(struct helper *m) {
    m->operation=EXIT;
    struct user_pt_regs r=md_task_registers_entry(m->abi.task_abort,m->stack,m->pstate);
    int error=registers(m,m->child,&r,1);
    return error==-ESRCH ? 0 : error ? error : resume(m,m->child);
}
static int clone_begin(struct helper *m) {
    m->parent_operation=CLONE;
    unsigned long args[6]={CLONE_VM|CLONE_THREAD|CLONE_SIGHAND,m->stack};
    if (m->borrowed) {
        struct md_task_call_arguments call={.number=SYS_clone};
        memcpy(call.value,args,sizeof(args));
        return md_task_call_again(&m->parent_call,&call);
    }
    return call(m,m->owner,SYS_clone,args);
}
static int clear_next(struct helper *m) {
    /* Only the captured socket/rights survive; unrelated pipe writers and
     * sockets must not be pinned for the duration of a blocking send. */
    while (io_helper(m) && m->keep_index<m->send.count) {
        unsigned keep=(unsigned)m->send.keep[m->keep_index++], first=m->clear_from;
        m->clear_from=keep+1;
        if (keep==first) continue;
        unsigned long args[6]={first,keep-1,0};
        return call(m,m->child,SYS_close_range,args);
    }
    if (m->clear_from!=UINT_MAX) {
        unsigned long args[6]={m->clear_from,UINT_MAX,0}; m->clear_from=UINT_MAX;
        return call(m,m->child,SYS_close_range,args);
    }
    if (io_helper(m)) {
        if (m->kind==MD_TASK_HELPER_SEND_CAPTURED) {
            int channel=m->send_address || m->send_inputs->count>1;
            m->operation=channel ? SEND_CHANNEL : SEND_SOCKET;
            unsigned long args[6]={MD_GUEST_TASK_HELPER,channel ? 0 : 2};
            return call(m,m->child,SYS_gettid,args);
        }
        m->operation=SEND_INPUT; m->keep_index=0;
        unsigned long args[6]={m->send.keep[0],F_GETFD};
        return call(m,m->child,SYS_fcntl,args);
    }
    m->operation=CHANNEL;
    unsigned long args[6]={MD_GUEST_TASK_HELPER,0};
    return call(m,m->child,SYS_gettid,args);
}
static int receive_export(struct helper *m) {
    struct md_task_receive_frame *p=receive_frame(m);
    if (!p || !p->count || p->count>253) return protocol_error(m,__LINE__);
    unsigned needed=(m->received ? m->received->count : 0)+p->count;
    if (needed>MD_SOCKET_RECEIVED_FDS*1024U+1) return -EOVERFLOW;
    int error=md_received_fds_reserve(&m->received,needed);
    if (error) { m->result=error; return abort_child(m); }
    error=md_fd_channel_capture_vector_begin(m->channel,m->group,p->count,m->export_fds,&m->capture);
    if (error) { m->result=error; return abort_child(m); }
    p->token=m->capture.request;
    p->vector=(struct iovec){(void *)(m->parcel+offsetof(struct md_task_receive_frame,token)),sizeof(p->token)};
    p->message=(struct msghdr){.msg_iov=(void *)(m->parcel+offsetof(struct md_task_receive_frame,vector)),.msg_iovlen=1,
        .msg_control=(void *)(m->parcel+offsetof(struct md_task_receive_frame,control)),.msg_controllen=CMSG_SPACE(p->count*sizeof(int))};
    memset(&p->control,0,p->message.msg_controllen);
    p->control.align=(struct cmsghdr){.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS,.cmsg_len=CMSG_LEN(p->count*sizeof(int))};
    memcpy(CMSG_DATA(&p->control.align),p->descriptors,p->count*sizeof(int));
    m->operation=RECEIVE_EXPORT;
    unsigned long args[6]={m->endpoint,m->parcel+offsetof(struct md_task_receive_frame,message),MSG_NOSIGNAL|MSG_DONTWAIT};
    return call(m,m->child,SYS_sendmsg,args);
}
static int receive_collect(struct helper *m) {
    m->operation=RECEIVE_COLLECT;
    struct user_pt_regs r=md_task_registers_entry(m->abi.task_collect,m->stack,m->pstate); r.regs[0]=m->parcel;
    int error=registers(m,m->child,&r,1);
    return error ? error : resume(m,m->child);
}
static int export_next(struct helper *m) {
    if (m->export_offset==m->export_count) {
        m->result=0; m->observed=1; return abort_child(m);
    }
    struct md_task_receive_frame *p=receive_frame(m);
    if (!p) return protocol_error(m,__LINE__);
    p->count=m->export_count-m->export_offset;
    if (p->count>253) p->count=253;
    memcpy(p->descriptors,m->export_inputs+m->export_offset,p->count*sizeof(int));
    return receive_export(m);
}
static int child_begin(struct helper *m) {
    unsigned long options=PTRACE_O_TRACESECCOMP|PTRACE_O_TRACEEXIT|PTRACE_O_EXITKILL|PTRACE_O_TRACESYSGOOD;
    int error=(int)trace(m,PTRACE_SETOPTIONS,m->child,NULL,(void *)options);
    uint64_t mask=md_signal_protocol_mask();
    if (!error) error=(int)trace(m,PTRACE_SETSIGMASK,m->child,(void *)sizeof(mask),&mask);
    if (error) return error==-ESRCH ? 0 : error;
    if (m->cancelled) return abort_child(m);
    m->operation=CLEAR;
    return clear_next(m);
}
static int child_adopt(struct helper *m,pid_t child) {
    if (m->child || m->parent_operation!=CLONE || child<=0) return protocol_error(m,__LINE__);
    m->child=child;
    if (m->captured) { m->captured(m->completion); m->captured=NULL; }
    int ready=m->manager->callbacks.newborn(child);
    md_task_wait_track(m->manager->callbacks.wait,&m->wait_member,child);
    if (ready==MD_TASK_BIRTH_EXIT) {
        m->operation=EXIT;
        return resume(m,child);
    }
    return ready<0 ? ready : ready==MD_TASK_BIRTH_READY ? child_begin(m) : 0;
}
static int observe(struct helper *m, const struct user_pt_regs *r) {
    if (m->observed || !m->started) return 0;
    long address=(long)r->regs[0];
    if (!md_task_registers_call_result(r,m->abi.task_return,m->abi.task_completed,SYS_mmap)) {
        fprintf(stderr,"guest-runtime: mapping return pc=%lx returned=%lx completed=%lx number=%lu saved=%lu result=%ld\n",
            (unsigned long)r->pc,(unsigned long)m->abi.task_return,(unsigned long)m->abi.task_completed,
            (unsigned long)r->regs[8],(unsigned long)r->regs[19],address);
        return protocol_error(m,__LINE__);
    }
    if (address<=0) {
        m->result=address<0 && address>=-4095 ? address : -ECANCELED;
        m->observed=1; return 0;
    }
    int found=md_mapping_observe(m->maps,&m->source,(uintptr_t)address,
        m->request.size,m->request.offset,!!(m->request.flags&MAP_SHARED));
    if (found<0) return found;
    m->result=found && !m->before ? address : -ECANCELED;
    m->observed=1; return 0;
}
static int pending_pipe(void *context,const siginfo_t *info) {
    if (info->si_signo!=SIGPIPE) return 0;
    *(siginfo_t *)context=*info; return 1;
}
static int advance(struct helper *m, long value) {
    if (m->operation==SEND && value==-EPIPE) {
        /* Preserve the actual kernel signal, not an invented signal for an
         * application filter that merely returned EPIPE. No signal is consumed. */
        int result=md_task_pending_scan(m->manager->callbacks.trace,m->child,pending_pipe,&m->signal);
        if (result<0) { m->result=result; m->observed=1; return abort_child(m); }
    }
    if (value<0 && m->operation!=RECEIVE) {
        m->result=value>=-4095 ? value : -EPROTO;
        m->observed=1;
        return abort_child(m);
    }
    unsigned long args[6]={0}; long nr=SYS_gettid;
    switch (m->operation) {
        case CLEAR:
            if (value) return protocol_error(m,__LINE__);
            return clear_next(m);
        case SEND_INPUT:
            if (++m->keep_index<m->send.count) {
                nr=SYS_fcntl; args[0]=m->send.keep[m->keep_index]; args[1]=F_GETFD;
            } else {
                m->operation=RECEIVE_CHANNEL;
                args[0]=MD_GUEST_TASK_HELPER;
            }
            break;
        case SEND_CHANNEL:
            /* SCM_RIGHTS preserves O_PATH; ADDFD cannot install it. Import
             * before the application socket so its slot replaces this channel. */
            if (value>INT_MAX) return protocol_error(m,__LINE__);
            m->endpoint=(int)value; m->operation=m->send_address ? SEND_ANCHOR : SEND_IMPORT; nr=SYS_recvmsg;
            args[0]=value; args[1]=m->send_import+offsetof(struct md_task_send_import,message);
            args[2]=MSG_CMSG_CLOEXEC|MSG_DONTWAIT;
            break;
        case SEND_ANCHOR: {
            struct md_buffer_span span;
            if (md_buffer_span(&m->buffer,&span)) return protocol_error(m,__LINE__);
            struct md_task_send_import *p=(void *)((char *)span.data+m->send_import-m->parcel);
            if (value!=sizeof(p->token) || p->token!=m->anchor_token) return protocol_error(m,__LINE__);
            if (p->message.msg_flags&MSG_CTRUNC) { m->result=-EMFILE; return abort_child(m); }
            if (p->message.msg_flags&MSG_TRUNC || p->message.msg_controllen!=sizeof(p->anchor)
                    || p->anchor.align.cmsg_level!=SOL_SOCKET || p->anchor.align.cmsg_type!=SCM_RIGHTS
                    || p->anchor.align.cmsg_len!=CMSG_LEN(sizeof(int))) return protocol_error(m,__LINE__);
            int fd=-1; memcpy(&fd,CMSG_DATA(&p->anchor.align),sizeof(fd));
            if (fd<0) return protocol_error(m,__LINE__);
            struct sockaddr_un *a=(void *)((char *)span.data+m->send_address-m->parcel);
            *a=(struct sockaddr_un){.sun_family=AF_UNIX};
            int n=snprintf(a->sun_path,sizeof(a->sun_path),"/proc/thread-self/fd/%d",fd);
            if (n<0 || (unsigned)n>=sizeof(a->sun_path)) return protocol_error(m,__LINE__);
            struct msghdr *message=(void *)((char *)span.data+m->send.message-m->parcel);
            message->msg_name=(void *)m->send_address;
            message->msg_namelen=offsetof(struct sockaddr_un,sun_path)+n+1;
            if (m->send_inputs->count>1) {
                p->message.msg_control=(void *)(m->send.token_slot-CMSG_LEN(0));
                p->message.msg_controllen=CMSG_SPACE((m->send_inputs->count-1)*sizeof(int));
                p->message.msg_flags=0; p->token=0;
                m->operation=SEND_IMPORT; nr=SYS_recvmsg;
                args[0]=m->endpoint; args[1]=m->send_import+offsetof(struct md_task_send_import,message);
                args[2]=MSG_CMSG_CLOEXEC|MSG_DONTWAIT;
            } else { m->operation=SEND_CLOSE; nr=SYS_close; args[0]=m->endpoint; }
            break;
        }
        case SEND_IMPORT: {
            struct md_buffer_span span;
            if (md_buffer_span(&m->buffer,&span)) return protocol_error(m,__LINE__);
            struct md_task_send_import *p=(void *)((char *)span.data+m->send_import-m->parcel);
            if (value!=sizeof(p->token) || p->token!=m->send_token) return protocol_error(m,__LINE__);
            /* Kernel SCM_RIGHTS publication reports quota and copy failures
             * alike as CTRUNC. Never send the successfully imported prefix. */
            if (p->message.msg_flags&MSG_CTRUNC) { m->result=-EMFILE; return abort_child(m); }
            if (p->message.msg_flags&MSG_TRUNC
                    || p->message.msg_controllen!=CMSG_SPACE((m->send_inputs->count-1)*sizeof(int))) return protocol_error(m,__LINE__);
            m->operation=SEND_CLOSE; nr=SYS_close; args[0]=m->endpoint;
            break;
        }
        case SEND_CLOSE:
            m->operation=SEND_SOCKET;
            args[0]=MD_GUEST_TASK_HELPER; args[1]=2;
            break;
        case SEND_SOCKET:
            if (value>INT_MAX) return protocol_error(m,__LINE__);
            m->send.socket=(int)value;
            m->operation=SEND; nr=SYS_sendmsg;
            args[0]=value; args[1]=m->send.message; args[2]=(unsigned)m->send.flags;
            if (m->send.sendto) {
                struct md_buffer_span span;
                if (md_buffer_span(&m->buffer,&span)) return protocol_error(m,__LINE__);
                struct msghdr *message=(void *)((char *)span.data+m->send.message-m->parcel);
                nr=SYS_sendto; args[1]=(uintptr_t)m->send.payload.iov_base; args[2]=m->send.payload.iov_len;
                args[3]=(unsigned)m->send.flags; args[4]=(uintptr_t)message->msg_name; args[5]=message->msg_namelen;
            }
            break;
        case SEND:
            m->result=value; m->observed=1;
            return abort_child(m);
        case RECEIVE_CHANNEL: {
            if (value>INT_MAX) return protocol_error(m,__LINE__);
            m->endpoint=(int)value; m->operation=RECEIVE;
            if (m->kind==MD_TASK_HELPER_EXPORT) return export_next(m);
            struct md_task_receive_frame *p=receive_frame(m);
            if (!p) return protocol_error(m,__LINE__);
            nr=p->input.batch ? SYS_recvmmsg : SYS_recvmsg;
            args[0]=p->input.socket; args[1]=p->input.messages;
            args[2]=p->input.batch ? p->input.count : (unsigned)p->input.flags;
            if (p->input.batch) { args[3]=(unsigned)p->input.flags; args[4]=p->input.timeout; }
            break;
        }
        case RECEIVE: {
            struct md_task_receive_frame *p=receive_frame(m);
            if (!p) return protocol_error(m,__LINE__);
            p->result=value; p->cursor=0; p->offset=0; p->total=0;
            if (value<0 && !p->input.batch) {
                m->result=0; m->observed=1; return abort_child(m);
            }
            return receive_collect(m);
        }
        case RECEIVE_EXPORT: {
            if (value!=sizeof(uint64_t)) return protocol_error(m,__LINE__);
            struct md_task_receive_frame *p=receive_frame(m);
            if (!p) return protocol_error(m,__LINE__);
            int error=md_fd_channel_capture_vector_finish(&m->capture);
            if (error) { m->result=error; return abort_child(m); }
            memcpy(m->received->fds+m->received->count,m->export_fds,p->count*sizeof(int));
            m->received->count+=p->count;
            if (m->kind==MD_TASK_HELPER_EXPORT) {
                m->export_offset+=p->count; return export_next(m);
            }
            return receive_collect(m);
        }
        case RECEIVE_COLLECT:
            if (value==1) return receive_export(m);
            if (value) return protocol_error(m,__LINE__);
            m->result=0; m->observed=1;
            return abort_child(m);
        case CHANNEL:
            if (value) return protocol_error(m,__LINE__);
            m->operation=OPEN; nr=SYS_openat;
            args[0]=AT_FDCWD; args[1]=m->parcel+offsetof(struct parcel,path); args[2]=m->flags;
            break;
        case OPEN: {
            if (value>INT32_MAX) return protocol_error(m,__LINE__);
            m->captured_fd=(int)value;
            if (m->capture_metadata) {
                m->operation=METADATA; nr=SYS_fstat;
                args[0]=value; args[1]=m->parcel+offsetof(struct parcel,metadata);
                break;
            }
            goto capture_directory;
        }
        case METADATA: {
            struct md_buffer_span observed;
            if (md_buffer_span(&m->buffer,&observed)) return protocol_error(m,__LINE__);
            m->metadata=((struct parcel *)observed.data)->metadata;
            value=m->captured_fd;
capture_directory:
            if (m->directory_limit) {
                m->operation=DIRECTORY; nr=SYS_getdents64;
                args[0]=m->captured_fd; args[1]=m->parcel+offsetof(struct parcel,path);
                args[2]=sizeof(((struct parcel *)0)->path);
                break;
            }
export_open:
            if (m->kind==MD_TASK_HELPER_CAPTURE && (m->flags&O_PATH) && !(m->flags&O_NOFOLLOW)) {
                /* The stopped helper owns a private table: this alias pins the
                 * object already opened under caller filters, not its pathname.
                 * O_PATH needs no data access (unlike SCM_RIGHTS file_receive).
                 * Stateful and nofollow descriptions retain exact FD transfer. */
                char path[64];
                snprintf(path,sizeof(path),"/proc/%d/fd/%d",m->child,(int)value);
                m->maps=open(path,m->flags);
                if (m->maps>=0) { m->result=0; return abort_child(m); }
                int error=errno;
                if (error!=EACCES && error!=EPERM && error!=ENOENT) {
                    m->result=-error; return abort_child(m);
                }
                /* Protected proc access can be denied independently of rights
                 * transfer. Keep the task-local export without changing identity. */
            }
            int error=md_fd_channel_capture_begin(m->channel,m->group,1,&m->capture);
            if (error) { m->result=error; return abort_child(m); }
            struct md_buffer_span span;
            if ((error=md_buffer_span(&m->buffer,&span))) return error;
            struct parcel *p=span.data;
            p->token=m->capture.request;
            p->vector=(struct iovec){(void *)m->parcel,sizeof(p->token)};
            p->message=(struct msghdr){.msg_iov=(void *)(m->parcel+offsetof(struct parcel,vector)),.msg_iovlen=1,
                .msg_control=(void *)(m->parcel+offsetof(struct parcel,control)),.msg_controllen=sizeof(p->control)};
            p->control.align=(struct cmsghdr){.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS,.cmsg_len=CMSG_LEN(sizeof(int))};
            int fd=(int)value; memcpy(CMSG_DATA(&p->control.align),&fd,sizeof(fd));
            m->operation=EXPORT; nr=SYS_sendmsg;
            args[1]=m->parcel+offsetof(struct parcel,message); args[2]=MSG_NOSIGNAL|MSG_DONTWAIT;
            break;
        }
        case DIRECTORY: {
            if (!value) { value=m->captured_fd; goto export_open; }
            if ((unsigned long)value>sizeof(((struct parcel *)0)->path)) return protocol_error(m,__LINE__);
            if ((size_t)value>m->directory_limit-m->directory_size) {
                m->result=-EOVERFLOW; return abort_child(m);
            }
            struct md_buffer_span span;
            if (md_buffer_span(&m->buffer,&span)) return protocol_error(m,__LINE__);
            size_t size=m->directory_size+(size_t)value;
            void *data=realloc(m->directory,size);
            if (!data) { m->result=-ENOMEM; return abort_child(m); }
            m->directory=data;
            memcpy((char *)data+m->directory_size,((struct parcel *)span.data)->path,(size_t)value);
            m->directory_size=size;
            nr=SYS_getdents64; args[0]=m->captured_fd;
            args[1]=m->parcel+offsetof(struct parcel,path); args[2]=sizeof(((struct parcel *)0)->path);
            break;
        }
        case EXPORT: {
            if (value!=sizeof(uint64_t)) return protocol_error(m,__LINE__);
            struct md_fd_channel_input input;
            int error=md_fd_channel_capture_finish(&m->capture,&input);
            if (error) { m->result=error; return abort_child(m); }
            m->maps=input.fd[0];
            if (descriptor_helper(m)) {
                m->result=0;
                return abort_child(m);
            }
            if (m->request.address) {
                m->before=md_mapping_observe(m->maps,&m->source,m->request.address,
                    m->request.size,m->request.offset,!!(m->request.flags&MAP_SHARED));
                if (m->before<0) { m->result=m->before; return abort_child(m); }
            }
            m->operation=BACKING; args[0]=MD_GUEST_TASK_HELPER; args[1]=1;
            break;
        }
        case BACKING:
            if (value) return protocol_error(m,__LINE__);
            m->operation=MAP; nr=SYS_mmap;
            args[0]=m->request.address; args[1]=m->request.size;
            args[2]=m->request.protection; args[3]=m->request.flags;
            args[5]=m->request.offset;
            break;
        case MAP:
            m->observed=1; m->result=value;
            return abort_child(m);
        default: return protocol_error(m,__LINE__);
    }
    return call(m,m->child,nr,args);
}
struct md_task_helpers *md_task_helpers_new(const struct md_task_helper_callbacks *callbacks) {
    if (!callbacks || !callbacks->trace || !callbacks->newborn || !callbacks->wait) return NULL;
    struct md_task_helpers *manager=calloc(1,sizeof(*manager));
    if (manager) manager->callbacks=*callbacks;
    return manager;
}
void md_task_helpers_destroy(struct md_task_helpers *manager) {
    if (!manager) return;
    while (manager->first) {
        manager->first->result=-ECANCELED;
        complete(manager->first);
    }
    free(manager);
}
unsigned md_task_helpers_count(const struct md_task_helpers *manager) { return manager ? manager->count : 0; }
int md_task_helpers_group_busy(const struct md_task_helpers *manager,pid_t group) {
    for (const struct helper *h=manager ? manager->first : NULL;h;h=h->next)
        if (h->group==group) return 1;
    return 0;
}
static int helper_begin(struct md_task_helpers *manager, pid_t owner, pid_t group, int boundary,
        const struct user_pt_regs *regs, const struct md_interception_abi *abi,
        struct md_interception_memory *memory, const struct md_interception_buffer *buffer,
        const struct md_task_helper_request *request, md_task_helper_complete callback, void *context) {
    if (!manager || owner<=0 || group<=0 || !regs || !abi || !memory || !request || !callback) return -EINVAL;
    if (request->kind!=MD_TASK_HELPER_MAP && request->kind!=MD_TASK_HELPER_CAPTURE
            && request->kind!=MD_TASK_HELPER_RECEIVE
            && request->kind!=MD_TASK_HELPER_FD_TABLE && request->kind!=MD_TASK_HELPER_EXPORT
            && request->kind!=MD_TASK_HELPER_SEND_CAPTURED) return -EINVAL;
    char directory[64];
    if (request->kind==MD_TASK_HELPER_FD_TABLE) {
        if (request->path || request->flags) return -EINVAL;
        snprintf(directory,sizeof(directory),"/proc/%d/fd",owner);
    }
    const char *path=request->kind==MD_TASK_HELPER_MAP ? "/proc/thread-self/maps" : request->path;
    if (request->kind==MD_TASK_HELPER_FD_TABLE) path=directory;
    if (request->kind!=MD_TASK_HELPER_RECEIVE && request->kind!=MD_TASK_HELPER_EXPORT
            && request->kind!=MD_TASK_HELPER_SEND_CAPTURED
            && (!path || !*path || strnlen(path,PATH_MAX)==PATH_MAX)) return -EINVAL;
    if (request->kind==MD_TASK_HELPER_SEND_CAPTURED && (!request->send_inputs || request->send_inputs->count<1
            || request->send_inputs->count>MD_TASK_SEND_INPUTS
            || !request->send.message || (request->send_inputs->count>1 && request->send.token_slot<CMSG_LEN(0)) || request->send.count
            || request->send.socket<0 || request->send.sendto>1 || request->path || request->flags)) return -EINVAL;
    if (request->kind==MD_TASK_HELPER_SEND_CAPTURED) {
        if (request->send.token_slot>UINTPTR_MAX-(request->send_inputs->count-1)*sizeof(int)) return -EINVAL;
        for (unsigned i=0;i<request->send_inputs->count;++i)
            if (request->send_inputs->fds[i]<0) return -EBADF;
    }
    if (request->kind==MD_TASK_HELPER_EXPORT && (!request->descriptors || !request->descriptor_count
            || request->descriptor_count>MD_TASK_SEND_INPUTS || !request->captured
            || request->path || request->flags)) return -EINVAL;
    if (request->kind==MD_TASK_HELPER_RECEIVE && request->receive.socket<0) return -EBADF;
    if (request->kind==MD_TASK_HELPER_RECEIVE && (!request->receive.messages
            || !request->receive.count || request->receive.count>1024 || request->receive.batch>1
            || (!request->receive.batch && request->receive.count!=1) || !request->captured)) return -EINVAL;
    if (request->kind==MD_TASK_HELPER_MAP && !request->mapping.size) return -EINVAL;
    if (request->capture_metadata && request->kind!=MD_TASK_HELPER_CAPTURE) return -EINVAL;
    if (request->directory_limit && (request->kind!=MD_TASK_HELPER_CAPTURE
            || !(request->flags&O_DIRECTORY) || (request->flags&O_PATH)
            || request->directory_limit>65536)) return -EINVAL;
    if (request->kind==MD_TASK_HELPER_CAPTURE) {
        struct md_proc_path proc=md_proc_path_for(path,owner,group);
        int fdinfo=proc.kind==MD_PROC_FDINFO && !*proc.tail && request->flags==O_CLOEXEC;
        enum md_proc_kind kind=proc.kind==MD_PROC_FOREIGN ? proc.foreign_kind : proc.kind;
        int mounts=(kind==MD_PROC_MOUNTS || kind==MD_PROC_MOUNTINFO)
            && !*proc.tail && request->flags==O_CLOEXEC;
        int process=(kind==MD_PROC_STATUS || kind==MD_PROC_STAT || kind==MD_PROC_LIMITS
                || (kind>=MD_PROC_IO && kind<=MD_PROC_STACK))
            && !*proc.tail && request->flags==O_CLOEXEC;
        /* O_DIRECTORY bounds readable capture to a directory, independently
         * of its pathname. Provider admission owns the target's authority. */
        int directory=!!(request->flags&O_DIRECTORY);
        unsigned allowed=O_PATH|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW;
        if (directory) allowed|=O_NONBLOCK|O_NOATIME|O_LARGEFILE;
        if ((!(request->flags&O_PATH) && !fdinfo && !mounts && !process && !directory)
                || request->flags&~allowed) return -EINVAL;
    }
    for (struct helper *m=manager->first;m;m=m->next) if (m->owner==owner) return -EBUSY;
    unsigned exporting=request->kind==MD_TASK_HELPER_EXPORT ? request->descriptor_count : 0;
    struct helper *m=calloc(1,sizeof(*m)+exporting*sizeof(int));
    if (!m) return -ENOMEM;
    *m=(struct helper){.manager=manager,.owner=owner,.group=group,.abi=*abi,.request=request->mapping,
        .kind=request->kind,.complete=callback,.flags=request->kind==MD_TASK_HELPER_MAP
            ? O_RDONLY|O_CLOEXEC : request->flags|O_CLOEXEC,
        .completion=context,.backing=-1,.maps=-1,.result=-ECANCELED,.pstate=regs->pstate,
        .capture_metadata=request->capture_metadata,.directory_limit=request->directory_limit,.borrowed=boundary>=0};
    if (m->kind==MD_TASK_HELPER_FD_TABLE) m->flags=O_RDONLY|O_DIRECTORY|O_CLOEXEC;
    if (m->kind==MD_TASK_HELPER_SEND_CAPTURED) {
        m->send=request->send;
        m->send_import=request->send_import;
        m->send_inputs=md_received_fds_retain(request->send_inputs);
        m->send_address=request->send_address; m->send_anchor=-1;
        m->captured=request->captured;
    }
    if (exporting) {
        m->export_count=exporting;
        memcpy(m->export_inputs,request->descriptors,exporting*sizeof(int));
        m->send.count=exporting;
        memcpy(m->send.keep,m->export_inputs,exporting*sizeof(int));
    }
    if (exporting) {
        m->captured=request->captured;
        for (unsigned i=0;i<m->send.count;++i) {
            if (m->send.keep[i]<0) { free(m); return -EBADF; }
            for (unsigned j=i;j && m->send.keep[j]<m->send.keep[j-1];--j) {
                int swap=m->send.keep[j]; m->send.keep[j]=m->send.keep[j-1]; m->send.keep[j-1]=swap;
            }
        }
        unsigned count=0;
        for (unsigned i=0;i<m->send.count;++i) {
            int fd=m->send.keep[i];
            if (!count || m->send.keep[count-1]!=fd) m->send.keep[count++]=fd;
        }
        m->send.count=count;
    }
    if (m->kind==MD_TASK_HELPER_RECEIVE) {
        m->send=(struct md_task_send){.socket=request->receive.socket,.count=1,.keep={request->receive.socket}};
        m->captured=request->captured;
    }
    m->memory=md_memory_fork(memory,1);
    int error=m->memory ? md_buffer_pin(buffer,&m->buffer) : -ENOMEM;
    struct md_buffer_span span;
    if (!error) error=md_buffer_span(&m->buffer,&span);
    if (!error && span.size<sizeof(struct parcel)) error=-EINVAL;
    if (!error && (m->kind==MD_TASK_HELPER_RECEIVE || exporting)
            && span.size<sizeof(struct md_task_receive_frame)) error=-EINVAL;
    if (!error) {
        m->parcel=md_memory_buffer_address(memory,&m->buffer);
        if (!m->parcel) error=-EINVAL;
        else if (path) { memset(span.data,0,sizeof(struct parcel)); strcpy(((struct parcel *)span.data)->path,path); }
        else if (m->kind==MD_TASK_HELPER_RECEIVE || exporting)
            *(struct md_task_receive_frame *)span.data=(struct md_task_receive_frame){.input=request->receive};
    }
    if (!error && request->kind==MD_TASK_HELPER_SEND_CAPTURED) {
        uintptr_t control=m->send.token_slot-CMSG_LEN(0);
        size_t control_size=CMSG_SPACE((m->send_inputs->count-1)*sizeof(int));
        if (m->send_import<m->parcel || m->send_import%_Alignof(struct md_task_send_import)
                || span.size<sizeof(struct md_task_send_import)
                || m->send_import-m->parcel>span.size-sizeof(struct md_task_send_import)
                || (m->send_inputs->count>1 && (!control || control%_Alignof(struct cmsghdr)
                    || control>UINTPTR_MAX-control_size))
                || (control<m->send_import+sizeof(struct md_task_send_import)
                    && m->send_import<control+control_size)) error=-EINVAL;
        else {
            struct md_task_send_import *p=(void *)((char *)span.data+m->send_import-m->parcel);
            *p=(struct md_task_send_import){
                .vector={(void *)(m->send_import+offsetof(struct md_task_send_import,token)),sizeof(p->token)},
                .message={.msg_iov=(void *)(m->send_import+offsetof(struct md_task_send_import,vector)),.msg_iovlen=1,
                    .msg_control=(void *)control,.msg_controllen=control_size}};
            if (m->send_address || m->send.sendto) {
                if ((m->send_address && (m->send_address<m->parcel || span.size<sizeof(struct sockaddr_un)
                        || m->send_address-m->parcel>span.size-sizeof(struct sockaddr_un)))
                        || m->send.message<m->parcel || span.size<sizeof(struct msghdr)
                        || m->send.message-m->parcel>span.size-sizeof(struct msghdr)) error=-EINVAL;
                else if (m->send_address) {
                    m->send_anchor=fcntl(request->send_anchor,F_DUPFD_CLOEXEC,0);
                    if (m->send_anchor<0) error=-errno;
                    p->message.msg_control=(void *)(m->send_import+offsetof(struct md_task_send_import,anchor));
                    p->message.msg_controllen=sizeof(p->anchor);
                }
            }
        }
    }
    if (!error && request->kind==MD_TASK_HELPER_MAP) {
        m->backing=fcntl(request->mapping.source,F_DUPFD_CLOEXEC,0);
        error=m->backing<0 ? -errno : md_mapping_source(m->backing,&m->source);
    }
    if (!error && (request->kind!=MD_TASK_HELPER_SEND_CAPTURED || m->send_address || m->send_inputs->count>1))
        error=md_fd_channel_new(&m->channel);
    if (!error && request->kind==MD_TASK_HELPER_SEND_CAPTURED && m->send_address)
        error=md_fd_channel_output_vector(m->channel,&m->send_anchor,1,&m->anchor_token);
    if (!error && request->kind==MD_TASK_HELPER_SEND_CAPTURED && m->send_inputs->count>1)
        error=md_fd_channel_output_vector(m->channel,m->send_inputs->fds+1,m->send_inputs->count-1,&m->send_token);
    if (!error && exporting) error=md_received_fds_reserve(&m->received,exporting);
    if (!error && !m->borrowed) {
        /* A lazy channel/client expansion may enter without an adapter shield.
         * Own asynchronous delivery while lending the caller's registers;
         * synchronous faults and the host SIGSYS gate retain their policy. */
        error=(int)trace(m,PTRACE_GETSIGMASK,owner,(void *)sizeof(m->parent_mask),&m->parent_mask);
        uint64_t mask=m->parent_mask|md_signal_async_mask();
        if (!error && mask!=m->parent_mask) {
            error=(int)trace(m,PTRACE_SETSIGMASK,owner,(void *)sizeof(mask),&mask);
            if (!error) { m->parent_shielded=1; m->parent_registers=*regs; }
        }
    }
    if (error) { dispose(m); return error; }
    m->next=manager->first; manager->first=m; ++manager->count;
    m->stack=md_memory_stack_take(memory);
    if (m->borrowed) {
        struct md_task_call_arguments call={.number=SYS_clone,
            .value={CLONE_VM|CLONE_THREAD|CLONE_SIGHAND,m->stack}};
        m->parent_operation=CLONE;
        if (!m->stack) {
            m->parent_operation=ALLOCATE;
            call=(struct md_task_call_arguments){.number=SYS_mmap,
                .value={0,MD_TASK_STACK_SIZE+2*(size_t)getpagesize(),PROT_NONE,
                    MAP_PRIVATE|MAP_ANONYMOUS,(unsigned long)-1,0}};
        }
        error=md_task_call_begin(&m->parent_call,manager->callbacks.trace,owner,
            (enum md_task_call_boundary)boundary,regs,abi->task_call,abi->task_return,&call);
        if (error && m->parent_call.stage) return error==-ESRCH ? 0 : error;
    } else if (m->stack) error=clone_begin(m);
    else {
        m->parent_operation=ALLOCATE;
        struct user_pt_regs r=*regs; r.pc=abi->allocate;
        error=registers(m,owner,&r,1);
        if (!error) error=resume(m,owner);
    }
    if (error && error!=-ESRCH) {
        int restored=parent_restore(m);
        if (restored) return restored;
        dispose(m); return error;
    }
    return 0;
}
int md_task_helper_begin(struct md_task_helpers *manager,pid_t owner,pid_t group,
        const struct user_pt_regs *regs,const struct md_interception_abi *abi,
        struct md_interception_memory *memory,const struct md_interception_buffer *buffer,
        const struct md_task_helper_request *request,md_task_helper_complete callback,void *context) {
    return helper_begin(manager,owner,group,-1,regs,abi,memory,buffer,request,callback,context);
}
int md_task_helper_borrow(struct md_task_helpers *manager,pid_t owner,pid_t group,
        enum md_task_call_boundary boundary,const struct user_pt_regs *regs,const struct md_interception_abi *abi,
        struct md_interception_memory *memory,const struct md_interception_buffer *buffer,
        const struct md_task_helper_request *request,md_task_helper_complete callback,void *context) {
    if (boundary<MD_TASK_CALL_USER || boundary>MD_TASK_CALL_EMULATED_ENTRY) return -EINVAL;
    return helper_begin(manager,owner,group,boundary,regs,abi,memory,buffer,request,callback,context);
}
struct md_task_call *md_task_helper_call(struct md_task_helpers *manager,pid_t owner) {
    for (struct helper *m=manager ? manager->first : NULL;m;m=m->next)
        if (m->owner==owner && m->borrowed && !m->parent_ready) return &m->parent_call;
    return NULL;
}
int md_task_helper_detach(struct md_task_helpers *manager, pid_t owner) {
    if (!manager) return 0;
    for (struct helper *m=manager->first;m;m=m->next) if (m->owner==owner) {
        m->owner=0; m->cancelled=1;
        if (!m->child || m->reaped) return complete(m);
        int error=(int)trace(m,PTRACE_INTERRUPT,m->child,NULL,NULL);
        return error==-ESRCH ? 0 : error;
    }
    return 0;
}
int md_task_helper_notification(struct md_task_helpers *manager, int listener, const struct seccomp_notif *q) {
    for (struct helper *m=manager ? manager->first : NULL;m;m=m->next) if (m->child==(pid_t)q->pid) {
        if (q->data.nr!=SYS_gettid || q->data.instruction_pointer!=m->abi.task_return
                || q->data.args[0]!=MD_GUEST_TASK_HELPER
                || q->data.args[1]!=(unsigned)(m->operation==SEND_SOCKET ? 2 : m->operation==BACKING)
                || q->data.args[2] || q->data.args[3] || q->data.args[4] || q->data.args[5]
                || (m->operation!=CHANNEL && m->operation!=BACKING && m->operation!=SEND_SOCKET
                    && m->operation!=SEND_CHANNEL && m->operation!=RECEIVE_CHANNEL)) return protocol_error(m,__LINE__);
        struct seccomp_notif_addfd add={.id=q->id,.flags=SECCOMP_ADDFD_FLAG_SEND
                |(m->operation==RECEIVE_CHANNEL
                    ? 0 : SECCOMP_ADDFD_FLAG_SETFD),
            .srcfd=m->operation==CHANNEL || m->operation==SEND_CHANNEL || m->operation==RECEIVE_CHANNEL
                ? md_fd_channel_endpoint(m->channel) : m->send_inputs ? m->send_inputs->fds[0] : m->backing,
            .newfd=m->operation==SEND_CHANNEL || m->operation==SEND_SOCKET ? (unsigned)m->send.socket : 0,
            .newfd_flags=O_CLOEXEC};
        ++m->notifications;
        int result=ioctl(listener,SECCOMP_IOCTL_NOTIF_ADDFD,&add);
        m->notification_result=result<0 ? -errno : result;
        if (result<0 && errno!=ENOENT) {
            int error=errno;
            /* SETFD reports an out-of-limit target as EBADF. The source socket
             * is retained and valid; this is private-table capacity failure. */
            if (error==EBADF && (m->operation==SEND_CHANNEL || m->operation==SEND_SOCKET)
                    && fcntl((int)add.srcfd,F_GETFD)>=0) error=EMFILE;
            struct seccomp_notif_resp reply={.id=q->id,.error=-error};
            if (ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) && errno!=ENOENT) return -errno;
        }
        return 1;
    }
    return 0;
}
static int borrowed_parent(struct helper *m,int status) {
    int ready=md_task_call_event(&m->parent_call,status);
    if (ready<=0) return ready;
    long value=m->parent_call.result;
    if (m->parent_operation==PROTECT_STACK) {
        int error=value<0 ? (int)value : 0;
        if (!error) {
            m->stack=m->stack_mapping+(size_t)getpagesize()+MD_TASK_STACK_SIZE;
            error=md_memory_stack_add(m->memory,m->stack);
        }
        if (error) {
            m->stack=0; m->result=error; m->parent_operation=RELEASE_STACK;
            struct md_task_call_arguments call={.number=SYS_munmap,
                .value={m->stack_mapping,MD_TASK_STACK_SIZE+2*(size_t)getpagesize()}};
            return md_task_call_again(&m->parent_call,&call);
        }
        return clone_begin(m);
    }
    if (value<0 || m->parent_operation==RELEASE_STACK) {
        if (m->parent_operation!=RELEASE_STACK) m->result=value;
        if (m->child) return protocol_error(m,__LINE__);
        return complete(m);
    }
    if (m->parent_operation==ALLOCATE) {
        m->stack_mapping=(uintptr_t)value;
        m->parent_operation=PROTECT_STACK;
        struct md_task_call_arguments call={.number=SYS_mprotect,
            .value={m->stack_mapping+(size_t)getpagesize(),MD_TASK_STACK_SIZE,PROT_READ|PROT_WRITE}};
        return md_task_call_again(&m->parent_call,&call);
    }
    if (m->parent_operation!=CLONE || !m->child || value!=m->child) return protocol_error(m,__LINE__);
    m->parent_ready=1;
    return m->reaped ? complete(m) : 0;
}
int md_task_helper_event(struct md_task_helpers *manager, pid_t pid, int status) {
    struct helper *m=manager ? manager->first : NULL;
    while (m && m->owner!=pid && m->child!=pid) m=m->next;
    if (!m) return 0;
    int child=pid==m->child;
    if (child) m->child_stopped=1;
    if (WIFEXITED(status) || WIFSIGNALED(status)) {
        if (!child) return 0;
        md_task_wait_untrack(manager->callbacks.wait,&m->wait_member);
        m->reaped=1;
        int error=(!m->owner || m->parent_ready) ? complete(m) : 0;
        return error ? error : 1;
    }
    if (!WIFSTOPPED(status)) return protocol_error(m,__LINE__);
    unsigned event=(unsigned)status>>16;
    int sig=WSTOPSIG(status), error=0;
    if (!child && (event==PTRACE_EVENT_EXIT || event==PTRACE_EVENT_EXEC)) {
        if (event==PTRACE_EVENT_EXIT && !m->child && m->parent_operation==CLONE) {
            /* Group exit can replace the parent's CLONE stop before wait4
             * observes it. Retain its exact child through terminal reaping. */
            long born=md_task_birth_terminal(manager->callbacks.trace,pid,SYS_clone,
                CLONE_VM|CLONE_THREAD|CLONE_SIGHAND);
            if (born<0) return born==-ESRCH ? 1 : (int)born;
            if (born) {
                m->cancelled=1;
                error=child_adopt(m,(pid_t)born);
                if (error && error!=-ESRCH) return error;
            }
        }
        return 0;
    }
    if (!child && !m->borrowed && !event && sig==SIGSYS && !m->child && m->parent_operation==CLONE) {
        siginfo_t info;
        error=(int)trace(m,PTRACE_GETSIGINFO,pid,NULL,&info);
        if (error) return error;
        if (info.si_code==SYS_SECCOMP && info.si_syscall==SYS_clone
                && (uintptr_t)info.si_call_addr==m->abi.task_return) {
            /* This denied syscall belongs to our private helper, not to the
             * application's continuation or SIGSYS handler. No child exists. */
            m->result=-EOPNOTSUPP; error=complete(m); return error ? error : 1;
        }
    }
    if (!child && !m->borrowed && event==PTRACE_EVENT_STOP && sig==SIGTRAP) {
        /* An interrupt can lose to an ordinary stop and arrive after the
         * caller lent us its registers. Continue the current gate, not the
         * caller's saved syscall. Application group-stops remain external. */
        if (m->parent_ready) return protocol_error(m,__LINE__);
        error=resume(m,pid);
    } else if (!child && event==PTRACE_EVENT_CLONE) {
        int terminal=0;
        long born=md_task_birth_read(manager->callbacks.trace,pid,event,&terminal);
        if (born<0) error=(int)born;
        else {
            m->cancelled|=terminal;
            error=child_adopt(m,(pid_t)born);
            if (!error && !terminal)
                error=m->borrowed ? md_task_call_resume(&m->parent_call,0) : resume(m,pid);
        }
    } else if (!child && m->borrowed) {
        error=borrowed_parent(m,status);
    } else if (child && !event && sig==SIGSTOP) {
        /* STOP targets the whole application group even when its delivery
         * selects this private thread. Do not consume it as helper failure. */
        error=(int)trace(m,PTRACE_CONT,pid,NULL,(void *)(uintptr_t)SIGSTOP);
        if (!error) m->child_stopped=0;
    } else if (child && event==PTRACE_EVENT_EXIT) {
        if (m->operation==MAP) {
            struct user_pt_regs r;
            error=registers(m,pid,&r,0);
            if (!error) error=observe(m,&r);
        }
        if (!error) error=resume(m,pid);
    } else if (child && event==PTRACE_EVENT_STOP) {
        if (sig!=SIGTRAP) {
            /* EVENT_WAIT: a private helper shares its caller's job control.
             * Preserve the group stop until CONT or cancellation instead of
             * repeatedly rearming an interrupt against the stopped group. */
            error=(int)trace(m,PTRACE_LISTEN,pid,NULL,NULL);
            if (!error) m->child_stopped=0;
            else if (error==-ESRCH) error=0;
        } else if (!m->operation) error=child_begin(m);
        else if (m->cancelled) {
            struct user_pt_regs r;
            error=registers(m,pid,&r,0);
            if (!error && m->operation==MAP) error=observe(m,&r);
            if (!error) error=abort_child(m);
        } else error=resume(m,pid);
    } else if (event==PTRACE_EVENT_SECCOMP) {
        struct user_pt_regs r;
        error=registers(m,pid,&r,0);
        if (!error && r.pc!=m->abi.task_return) return protocol_error(m,__LINE__);
        if (!error) {
            if (child && m->operation==MAP) m->started=1;
            error=resume(m,pid);
        }
    } else if (event==MD_TASK_CHECKPOINT_EVENT) {
        struct user_pt_regs r;
        error=registers(m,pid,&r,0);
        if (!error && !child && m->parent_operation==ALLOCATE && r.pc==m->abi.allocated+4) {
            long top=r.regs[0];
            if (top<0) { m->result=top; error=complete(m); return error ? error : 1; }
            if (!top || top%(size_t)getpagesize()) return protocol_error(m,__LINE__);
            m->stack=top;
            error=md_memory_stack_add(m->memory,m->stack);
            if (!error) error=clone_begin(m);
        } else if (!error && child && m->operation==RECEIVE_COLLECT && r.pc==m->abi.task_collected+4) {
            error=advance(m,(long)r.regs[0]);
        } else if (!error && r.pc==m->abi.task_completed+4) {
            if (child) error=advance(m,(long)r.regs[0]);
            else {
                if ((long)r.regs[0]<0) {
                    if (m->child) return protocol_error(m,__LINE__);
                    m->result=(long)r.regs[0]; error=complete(m); return error ? error : 1;
                }
                if (!m->child || r.regs[0]!=(unsigned)m->child) return protocol_error(m,__LINE__);
                /* EVENT_WAIT: exact helper reaping. Completion owns the caller
                 * continuation; no userspace wait or settling delay is used. */
                m->parent_ready=1;
                if (m->reaped) { error=complete(m); return error ? error : 1; }
            }
        } else if (!error) return protocol_error(m,__LINE__);
    } else if (child) {
        m->result=-EINTR;
        if (m->operation==MAP) {
            struct user_pt_regs r;
            error=registers(m,pid,&r,0);
            if (!error) error=observe(m,&r);
        }
        if (!error) error=abort_child(m);
    } else return 0;
    return error && error!=-ESRCH ? error : 1;
}
int md_task_helpers_visit(struct md_task_helpers *manager,struct md_interception_memory *memory,
        int (*visit)(void *,pid_t,int,int),void *context) {
    for (struct helper *m=manager ? manager->first : NULL;m;m=m->next) {
        if (memory && m->memory!=memory) continue;
        int error=m->owner ? visit(context,m->owner,m->parent_ready,PTRACE_CONT) : 0;
        /* Before child_begin, EVENT_STOP is its initial stop, not a barrier
         * interrupt. Only the helper owner may initialize and resume it. */
        if (!error && m->child && !m->reaped)
            error=visit(context,m->child,m->child_stopped,m->operation ? PTRACE_CONT : 0);
        if (error) return error;
    }
    return 0;
}
int md_task_helper_owns(struct md_task_helpers *manager,pid_t owner,pid_t task) {
    for (struct helper *m=manager ? manager->first : NULL;m;m=m->next)
        if (m->owner==owner && (m->owner==task || m->child==task)) return 1;
    return 0;
}
void md_task_helpers_describe(struct md_task_helpers *manager,pid_t owner,int output) {
    for (struct helper *m=manager ? manager->first : NULL;m;m=m->next) if (m->owner==owner) {
        dprintf(output,"Shroot: helper owner=%d child=%d kind=%d parent=%d operation=%d ready=%d reaped=%d stopped=%d cancelled=%d started=%d\n",
            m->owner,m->child,m->kind,m->parent_operation,m->operation,
            m->parent_ready,m->reaped,m->child_stopped,m->cancelled,m->started);
        const pid_t actors[]={m->owner,m->child};
        dprintf(output,"Shroot: helper notifications=%u result=%d\n",m->notifications,m->notification_result);
        for (unsigned i=0;i<2;++i) if (actors[i]) {
            struct user_pt_regs r={0};
            int error=registers(m,actors[i],&r,0);
            dprintf(output,"Shroot: helper actor=%d pc=%#llx syscall=%llu result=%lld error=%d\n",
                actors[i],(unsigned long long)r.pc,(unsigned long long)r.regs[8],(long long)r.regs[0],error);
            char path[80],state[256];
            snprintf(path,sizeof(path),"/proc/%d/task/%d/syscall",m->group,actors[i]);
            int fd=open(path,O_RDONLY|O_CLOEXEC);
            ssize_t size=fd<0 ? -1 : read(fd,state,sizeof(state)-1);
            int failure=size<0 ? errno : 0;
            if (fd>=0) close(fd);
            if (size>0 && state[size-1]=='\n') --size;
            if (size>=0) state[size]=0;
            dprintf(output,"Shroot: helper actor=%d syscall=%s error=%d\n",
                actors[i],size>=0 ? state : "unavailable",failure);
        }
    }
}
struct md_interception_memory *md_task_helper_memory(struct md_task_helpers *manager,pid_t task) {
    for (struct helper *m=manager ? manager->first : NULL;m;m=m->next)
        if (m->owner==task || m->child==task) return m->memory;
    return NULL;
}
int md_task_helper_retiring(struct md_task_helpers *manager,pid_t group,pid_t task) {
    for (struct helper *m=manager ? manager->first : NULL;m;m=m->next)
        if (m->group==group && m->child==task && !m->reaped && m->operation==EXIT) return 1;
    return 0;
}
