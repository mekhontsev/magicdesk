#define _GNU_SOURCE
#include "seccomp_notify.h"
#include "control_event.h"
#include "resource_order.h"
#include "pid_namespace.h"
#include "task_resources.h"
#include "fd_install.h"
#include "guest_identity.h"
#include "received_fds.h"
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>

enum state { QUEUED, SENT, REPLIED, CANCELLED };
struct md_seccomp_notification {
    struct md_seccomp_notify *owner;
    struct md_seccomp_notification *previous,*next,*ready_previous,*ready_next;
    struct md_seccomp_notification *indexed_next;
    struct seccomp_notif message;
    struct seccomp_notif_resp response;
    struct md_seccomp_notification_kernel kernel;
    struct md_identity identity;
    enum state state;
    unsigned references;
    unsigned pid_count;
    struct md_pid_number_value pids[];
};
struct md_seccomp_notify {
    atomic_uint references;
    pthread_mutex_t lock;
    struct md_resource_wakeup *wakeup;
    struct md_control_event *events;
    struct md_control_cursor cursor;
    struct md_seccomp_notification *first,*last,*ready_first,*ready_last;
    struct md_seccomp_notification **index;
    size_t capacity,count;
    uint64_t sequence;
    size_t queued,sent;
    int closed,active;
};
struct receive {
    struct md_control_pending pending;
    struct md_seccomp_notify *owner;
    struct md_seccomp_notification *message;
    struct md_pid_namespace *view;
};
struct addfd {
    struct md_control_pending pending;
    struct md_seccomp_notification *message;
    struct md_received_fds *source;
    struct md_fd_install install;
    struct md_control_pending *closing;
    long result;
    int completed;
};
static void notification_destroy(struct md_seccomp_notification *n) {
    md_fd_origins_unpin(n->kernel.files);
    md_identity_release(&n->identity);
    md_seccomp_notify_release(n->owner); free(n);
}
static void changed(struct md_seccomp_notify *q) {
    md_control_event_publish(q->events);
    md_resource_wakeup_signal(q->wakeup);
}
struct md_seccomp_notify *md_seccomp_notify_new(struct md_resource_wakeup *wakeup) {
    struct md_seccomp_notify *q=calloc(1,sizeof(*q));
    if (!q) return NULL;
    int error=pthread_mutex_init(&q->lock,NULL);
    if (error) { free(q); errno=error; return NULL; }
    q->events=md_control_event_new();
    if (!q->events) { pthread_mutex_destroy(&q->lock); free(q); return NULL; }
    atomic_init(&q->references,1);
    q->wakeup=md_resource_wakeup_retain(wakeup); q->active=1;
    md_control_cursor_open(&q->cursor,q->events);
    return q;
}
struct md_seccomp_notify *md_seccomp_notify_retain(struct md_seccomp_notify *q) {
    if (q) atomic_fetch_add_explicit(&q->references,1,memory_order_relaxed);
    return q;
}
void md_seccomp_notify_release(struct md_seccomp_notify *q) {
    if (!q || atomic_fetch_sub_explicit(&q->references,1,memory_order_acq_rel)!=1) return;
    md_control_cursor_close(&q->cursor); md_control_event_release(q->events);
    md_resource_wakeup_release(q->wakeup);
    pthread_mutex_destroy(&q->lock); free(q->index); free(q);
}
static int index_prepare(struct md_seccomp_notify *q) {
    if (q->count<q->capacity/2) return 0;
    if (q->capacity>SIZE_MAX/2/sizeof(*q->index)) return -ENOMEM;
    size_t capacity=q->capacity ? q->capacity*2 : 16;
    struct md_seccomp_notification **index=calloc(capacity,sizeof(*index));
    if (!index) return -ENOMEM;
    for (struct md_seccomp_notification *n=q->first;n;n=n->next) {
        size_t bucket=n->message.id&(capacity-1);
        n->indexed_next=index[bucket]; index[bucket]=n;
    }
    free(q->index); q->index=index; q->capacity=capacity; return 0;
}
static void dequeue(struct md_seccomp_notification *n) {
    struct md_seccomp_notify *q=n->owner;
    if (n->ready_previous) n->ready_previous->ready_next=n->ready_next; else q->ready_first=n->ready_next;
    if (n->ready_next) n->ready_next->ready_previous=n->ready_previous; else q->ready_last=n->ready_previous;
    n->ready_previous=n->ready_next=NULL; --q->queued;
}
static void enqueue(struct md_seccomp_notification *n) {
    struct md_seccomp_notify *q=n->owner;
    n->ready_previous=q->ready_last; n->ready_next=NULL;
    if (q->ready_last) q->ready_last->ready_next=n; else q->ready_first=n;
    q->ready_last=n; ++q->queued; n->state=QUEUED;
}
static void reply(struct md_seccomp_notification *n,const struct seccomp_notif_resp *response) {
    if (n->state==QUEUED) dequeue(n);
    else if (n->state==SENT) --n->owner->sent;
    n->response=*response; n->state=REPLIED;
}
static int valid(struct md_seccomp_notification *n) {
    if (n->kernel.listener<0) return 0;
    return ioctl(n->kernel.listener,SECCOMP_IOCTL_NOTIF_ID_VALID,&n->kernel.id) ? -errno : 0;
}
static int commit(struct md_seccomp_notification *n,const struct seccomp_notif_resp *response) {
    if (n->kernel.listener<0) return 0;
    if ((response->flags&SECCOMP_USER_NOTIF_FLAG_CONTINUE) && !n->kernel.borrowed) return valid(n);
    struct seccomp_notif_resp native=*response;
    native.id=n->kernel.id; native.flags=0;
    return ioctl(n->kernel.listener,SECCOMP_IOCTL_NOTIF_SEND,&native) ? -errno : 0;
}
static void cancel(struct md_seccomp_notification *n) {
    if (n->state==QUEUED) dequeue(n);
    else if (n->state==SENT) --n->owner->sent;
    n->state=CANCELLED;
}
void md_seccomp_notify_close(struct md_seccomp_notify *q) {
    if (!q) return;
    pthread_mutex_lock(&q->lock);
    if (!q->closed) {
        q->closed=1;
        for (struct md_seccomp_notification *n=q->first;n;n=n->next)
            if (n->state!=REPLIED && n->state!=CANCELLED) {
                struct seccomp_notif_resp response={.id=n->message.id,.error=-ENOSYS};
                if (commit(n,&response)) cancel(n); else reply(n,&response);
            }
        changed(q);
    }
    pthread_mutex_unlock(&q->lock);
}
void md_seccomp_notify_active(struct md_seccomp_notify *q,int active) {
    pthread_mutex_lock(&q->lock);
    if (q->active!=!!active) { q->active=!!active; changed(q); }
    pthread_mutex_unlock(&q->lock);
}
int md_seccomp_notify_begin(struct md_seccomp_notify *q,unsigned pid,const struct md_pid_identity *identity,
        const struct seccomp_data *data,struct md_seccomp_notification **out) {
    return md_seccomp_notify_begin_kernel(q,pid,identity,data,NULL,out);
}
int md_seccomp_notify_begin_kernel(struct md_seccomp_notify *q,unsigned pid,const struct md_pid_identity *identity,
        const struct seccomp_data *data,const struct md_seccomp_notification_kernel *kernel,
        struct md_seccomp_notification **out) {
    if (!q || !data || !out || *out) return -EINVAL;
    struct md_pid_number_value numbers[MD_PID_LEVELS];
    unsigned count=md_pid_identity_export(identity,numbers);
    struct md_seccomp_notification *n=calloc(1,sizeof(*n)+count*sizeof(*numbers));
    if (!n) return -ENOMEM;
    n->kernel=kernel ? *kernel : (struct md_seccomp_notification_kernel){.listener=-1};
    n->kernel.files=md_fd_origins_pin(n->kernel.files);
    if (n->kernel.identity) n->identity=md_identity_copy(n->kernel.identity);
    n->kernel.identity=NULL;
    n->pid_count=count; memcpy(n->pids,numbers,count*sizeof(*numbers));
    pthread_mutex_lock(&q->lock);
    int error=q->closed ? -ENOSYS : q->sequence==UINT64_MAX ? -EOVERFLOW : 0;
    if (!error) error=index_prepare(q);
    if (!error) {
        n->owner=md_seccomp_notify_retain(q); n->references=1;
        n->message=(struct seccomp_notif){.id=++q->sequence,.pid=pid,.data=*data};
        size_t bucket=n->message.id&(q->capacity-1);
        n->indexed_next=q->index[bucket]; q->index[bucket]=n; ++q->count;
        n->previous=q->last;
        if (q->last) q->last->next=n; else q->first=n;
        q->last=n; enqueue(n); changed(q); *out=n;
    }
    pthread_mutex_unlock(&q->lock);
    if (error) notification_destroy(n);
    return error;
}
int md_seccomp_notification_result(struct md_seccomp_notification *n,struct seccomp_notif_resp *out) {
    if (!n || !out) return -EINVAL;
    pthread_mutex_lock(&n->owner->lock);
    int ready=n->state==REPLIED ? 1 : n->state==CANCELLED ? -ENOENT : 0;
    if (ready>0) *out=n->response;
    pthread_mutex_unlock(&n->owner->lock);
    return ready;
}
void md_seccomp_notification_release(struct md_seccomp_notification *n) {
    if (!n) return;
    struct md_seccomp_notify *q=n->owner;
    pthread_mutex_lock(&q->lock);
    if (n->state==QUEUED) dequeue(n);
    else if (n->state==SENT) --q->sent;
    if (n->previous) n->previous->next=n->next; else q->first=n->next;
    if (n->next) n->next->previous=n->previous; else q->last=n->previous;
    struct md_seccomp_notification **slot=&q->index[n->message.id&(q->capacity-1)];
    while (*slot!=n) slot=&(*slot)->indexed_next;
    *slot=n->indexed_next; n->indexed_next=NULL; --q->count;
    n->next=n->previous=NULL; n->state=CANCELLED;
    int retired=!--n->references; changed(q);
    pthread_mutex_unlock(&q->lock);
    if (retired) notification_destroy(n);
}
static struct md_seccomp_notification *find(struct md_seccomp_notify *q,uint64_t id) {
    if (!q->capacity) return NULL;
    for (struct md_seccomp_notification *n=q->index[id&(q->capacity-1)];n;n=n->indexed_next)
        if (n->message.id==id) return n;
    return NULL;
}
static void *retain(void *q) { return md_seccomp_notify_retain(q); }
static void release(void *q) { md_seccomp_notify_release(q); }
static int spec(void *context,unsigned command,struct md_control_ioctl *out) {
    (void)context;
    switch (command) {
    case SECCOMP_IOCTL_NOTIF_RECV:
        *out=(struct md_control_ioctl){.size=sizeof(struct seccomp_notif),
            .direction=MD_CONTROL_IN|MD_CONTROL_OUT|MD_CONTROL_ACK}; return 0;
    case SECCOMP_IOCTL_NOTIF_SEND:
        *out=(struct md_control_ioctl){.size=sizeof(struct seccomp_notif_resp),.direction=MD_CONTROL_IN}; return 0;
    case SECCOMP_IOCTL_NOTIF_ADDFD:
        *out=(struct md_control_ioctl){.size=sizeof(struct seccomp_notif_addfd),.direction=MD_CONTROL_IN|MD_CONTROL_FD,
            .descriptor=offsetof(struct seccomp_notif_addfd,srcfd)}; return 0;
    case SECCOMP_IOCTL_NOTIF_ID_VALID:
    case _IOR(SECCOMP_IOC_MAGIC,2,__u64):
        *out=(struct md_control_ioctl){.size=sizeof(uint64_t),.direction=MD_CONTROL_IN}; return 0;
    default: return -EINVAL;
    }
}
static long receive_resume(struct md_control_pending *pending,void *data,size_t size) {
    struct receive *r=(void *)pending;
    struct md_seccomp_notify *q=r->owner;
    if (!data || size!=sizeof(struct seccomp_notif)) return -EINVAL;
    pthread_mutex_lock(&q->lock);
    long result=r->message ? -EALREADY : q->closed ? -ENOENT : -EINPROGRESS;
    while (!r->message && !q->closed && q->ready_first) {
        struct md_seccomp_notification *n=q->ready_first;
        int live=valid(n);
        if (live==-ENOENT) { cancel(n); changed(q); continue; }
        if (live) { result=live; break; }
        dequeue(n); n->state=SENT; ++q->sent; ++n->references; r->message=n;
        memcpy(data,&n->message,sizeof(n->message)); result=0; changed(q);
        ((struct seccomp_notif *)data)->pid=md_pid_numbers_visible(n->pids,
            n->pid_count*sizeof(*n->pids),r->view,n->message.pid);
        break;
    }
    pthread_mutex_unlock(&q->lock);
    return result;
}
static void receive_copied(struct md_control_pending *pending,long result) {
    struct receive *r=(void *)pending;
    struct md_seccomp_notify *q=r->owner;
    pthread_mutex_lock(&q->lock);
    struct md_seccomp_notification *n=r->message;
    int retired=0;
    if (n) {
        /* A concurrent SEND is a commit witness. A failed output copy must not
         * resurrect a replied or cancelled caller. */
        if (result<0 && n->state==SENT) { --q->sent; enqueue(n); changed(q); }
        r->message=NULL;
        retired=!--n->references;
    }
    pthread_mutex_unlock(&q->lock);
    if (retired) notification_destroy(n);
}
static void receive_dispose(struct md_control_pending *pending) {
    struct receive *r=(void *)pending;
    if (r->message) receive_copied(pending,-ECANCELED);
    md_pid_namespace_release(r->view);
    md_seccomp_notify_release(r->owner); free(r);
}
static struct md_control_actor addfd_actor(struct addfd *a) {
    struct md_seccomp_notification *n=a->message;
    return (struct md_control_actor){.pid=(int)n->message.pid,.identity=&n->identity,.wakeup=n->owner->wakeup};
}
static long addfd_resume(struct md_control_pending *pending,void *data,size_t size) {
    (void)data; (void)size;
    struct addfd *a=(void *)pending;
    struct md_seccomp_notification *n=a->message;
    struct md_seccomp_notify *q=n->owner;
    if (!a->completed) {
        pthread_mutex_lock(&q->lock);
        int error=n->state!=SENT ? -ENOENT : valid(n);
        pthread_mutex_unlock(&q->lock);
        long result=error ? error : md_fd_install_progress(&a->install);
        if (result==MD_FD_INSTALL_WAIT) return -EINPROGRESS;
        if (result==MD_FD_INSTALL_CANCELLED) result=-ENOENT;
        if (error) md_fd_install_cancel(&a->install);
        a->completed=1; a->result=result;
        if (result>=0 && (a->install.add_flags&SECCOMP_ADDFD_FLAG_SEND)) {
            /* ADDFD_SEND is the commit witness, even if a concurrent close
             * already observed the now-expired kernel notification. */
            struct seccomp_notif_resp response={.id=n->message.id,.val=result};
            pthread_mutex_lock(&q->lock);
            reply(n,&response); changed(q);
            pthread_mutex_unlock(&q->lock);
        }
    }
    struct md_control_actor actor=addfd_actor(a);
    while (a->closing || a->install.closed.first) {
        long result=a->closing ? a->closing->resume(a->closing,NULL,0)
            : md_fd_closed_next(&a->install.closed,&actor,&a->closing);
        if (result==-EINPROGRESS && a->closing) return result;
        if (a->closing) { a->closing->dispose(a->closing); a->closing=NULL; }
    }
    return a->result;
}
static void addfd_dispose(struct md_control_pending *pending) {
    struct addfd *a=(void *)pending;
    md_fd_install_cancel(&a->install);
    if (a->closing) a->closing->dispose(a->closing);
    struct md_control_actor actor=addfd_actor(a);
    while (a->install.closed.first) {
        struct md_control_pending *closing=NULL;
        md_fd_closed_next(&a->install.closed,&actor,&closing);
        if (closing) closing->dispose(closing);
    }
    md_received_fds_release(a->source);
    struct md_seccomp_notification *n=a->message;
    pthread_mutex_lock(&n->owner->lock);
    int retired=!--n->references;
    pthread_mutex_unlock(&n->owner->lock);
    if (retired) notification_destroy(n);
    free(a);
}
static long addfd_begin(struct md_seccomp_notify *q,const struct md_control_actor *actor,
        const struct seccomp_notif_addfd *input,struct md_control_pending **pending) {
    if (input->flags&~(SECCOMP_ADDFD_FLAG_SEND|SECCOMP_ADDFD_FLAG_SETFD)
            || input->newfd_flags&~O_CLOEXEC || (!(input->flags&SECCOMP_ADDFD_FLAG_SETFD) && input->newfd)) return -EINVAL;
    if (!actor || !actor->descriptor || actor->descriptor->count!=1
            || input->srcfd!=(unsigned)actor->descriptor_number) return -EBADF;
    struct addfd *a=calloc(1,sizeof(*a));
    if (!a) return -ENOMEM;
    pthread_mutex_lock(&q->lock);
    struct md_seccomp_notification *n=find(q,input->id);
    int error=!n || n->state!=SENT ? -ENOENT : valid(n);
    if (!error && !n->kernel.files) error=-EOPNOTSUPP;
    if (!error) { ++n->references; a->message=n; }
    pthread_mutex_unlock(&q->lock);
    if (error) { free(a); return error; }
    a->source=md_received_fds_retain(actor->descriptor);
    a->pending=(struct md_control_pending){.resume=addfd_resume,.dispose=addfd_dispose,.deadline=INT64_MAX};
    error=md_fd_install_begin(&a->install,n->kernel.files,n->kernel.listener,n->kernel.id,
        a->source->fds[0],a->source->origins ? a->source->origins[0] : NULL,input->newfd_flags,q->wakeup);
    if (!error) error=md_fd_install_target(&a->install,input->flags,input->newfd);
    if (error) { addfd_dispose(&a->pending); return error; }
    *pending=&a->pending;
    return addfd_resume(*pending,NULL,0);
}
static long ioctl_call(void *context,const struct md_control_actor *actor,unsigned command,
        unsigned long value,void *data,size_t size,struct md_control_pending **pending) {
    (void)value;
    struct md_seccomp_notify *q=context;
    struct md_control_ioctl layout;
    int error=spec(context,command,&layout);
    if (error) return error;
    if (!data || size!=layout.size || !pending || *pending) return -EINVAL;
    if (command==SECCOMP_IOCTL_NOTIF_ADDFD) {
        struct seccomp_notif_addfd input; memcpy(&input,data,sizeof(input));
        return addfd_begin(q,actor,&input,pending);
    }
    if (command==SECCOMP_IOCTL_NOTIF_RECV) {
        struct seccomp_notif input;
        memcpy(&input,data,sizeof(input));
        const unsigned char *bytes=(void *)&input;
        for (size_t i=0;i<sizeof(input);++i) if (bytes[i]) return -EINVAL;
        struct receive *r=calloc(1,sizeof(*r));
        if (!r) return -ENOMEM;
        r->owner=md_seccomp_notify_retain(q);
        r->view=md_pid_namespace_retain(actor && actor->resources
            ? md_pid_identity_namespace(actor->resources->pid) : NULL);
        r->pending=(struct md_control_pending){.resume=receive_resume,.dispose=receive_dispose,
            .copied=receive_copied,.deadline=INT64_MAX};
        *pending=&r->pending;
        return receive_resume(*pending,data,size);
    }
    struct seccomp_notif_resp response={0};
    uint64_t id;
    if (command==SECCOMP_IOCTL_NOTIF_SEND) {
        memcpy(&response,data,sizeof(response)); id=response.id;
        if (response.flags&~SECCOMP_USER_NOTIF_FLAG_CONTINUE
                || ((response.flags&SECCOMP_USER_NOTIF_FLAG_CONTINUE) && (response.error || response.val))) return -EINVAL;
    } else memcpy(&id,data,sizeof(id));
    pthread_mutex_lock(&q->lock);
    struct md_seccomp_notification *n=find(q,id);
    long result=!n || n->state==CANCELLED ? -ENOENT : n->state==SENT ? 0
        : command==SECCOMP_IOCTL_NOTIF_SEND ? -EINPROGRESS : -ENOENT;
    if (!result) {
        result=command==SECCOMP_IOCTL_NOTIF_SEND ? commit(n,&response) : valid(n);
        if (result==-ENOENT) { cancel(n); changed(q); }
    }
    if (!result && command==SECCOMP_IOCTL_NOTIF_SEND) { reply(n,&response); changed(q); }
    pthread_mutex_unlock(&q->lock);
    return result;
}
static long poll_state(void *context) {
    struct md_seccomp_notify *q=context;
    pthread_mutex_lock(&q->lock);
    int error=md_control_cursor_poll(&q->cursor);
    long mask=(q->queued ? POLLIN|POLLRDNORM : 0)|(q->sent ? POLLOUT|POLLWRNORM : 0)
        |(!q->active || q->closed ? POLLHUP : 0);
    pthread_mutex_unlock(&q->lock);
    return error<0 ? error : mask;
}
static int poll_descriptor(void *context,int *descriptor) {
    struct md_seccomp_notify *q=context;
    int fd=md_control_cursor_watch(&q->cursor);
    if (fd<0) return fd;
    *descriptor=fd; return 0;
}
static const struct md_control_file_ops ops={.retain=retain,.release=release,
    .ioctl_spec=spec,.ioctl_async=ioctl_call,.poll=poll_state,.poll_descriptor=poll_descriptor};
struct md_control_file md_seccomp_notify_control(struct md_seccomp_notify *q) {
    return q ? (struct md_control_file){&ops,q} : (struct md_control_file){0};
}
