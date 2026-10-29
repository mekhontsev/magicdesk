#define _GNU_SOURCE
#include "fuse_transport.h"
#include "resource_order.h"
#include "event_signal.h"
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>

struct md_fuse_request {
    atomic_uint references;
    struct md_fuse_connection *connection;
    struct md_resource_wakeup *wakeup;
    struct md_fuse_request *next,*queue_next;
    uint64_t unique;
    size_t input_size,capacity,reply_size;
    int queued,done,no_reply,error;
    void (*abandoned)(void *,int,const void *,size_t);
    void *abandoned_context;
    unsigned char data[];
};
struct md_fuse_connection {
    atomic_uint references;
    pthread_mutex_t lock;
    struct md_fuse_request *requests,*first,*last,*offered;
    struct md_resource_wakeup *reader;
    uint64_t sequence;
    size_t bytes;
    unsigned count;
    int event,error,claimed;
    int (*notify)(void *,unsigned,const void *,size_t);
    void *notify_context;
};
int md_fuse_connection_new(struct md_fuse_connection **out) {
    *out=NULL;
    struct md_fuse_connection *c=calloc(1,sizeof(*c));
    if (!c) return -ENOMEM;
    int error=pthread_mutex_init(&c->lock,NULL);
    if (error) { free(c); return -error; }
    c->event=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
    if (c->event<0) { error=-errno; pthread_mutex_destroy(&c->lock); free(c); return error; }
    atomic_init(&c->references,1); *out=c; return 0;
}
struct md_fuse_connection *md_fuse_connection_retain(struct md_fuse_connection *c) {
    if (c) atomic_fetch_add_explicit(&c->references,1,memory_order_relaxed);
    return c;
}
void md_fuse_connection_release(struct md_fuse_connection *c) {
    if (!c || atomic_fetch_sub_explicit(&c->references,1,memory_order_acq_rel)!=1) return;
    close(c->event); md_resource_wakeup_release(c->reader); pthread_mutex_destroy(&c->lock); free(c);
}
void md_fuse_request_release(struct md_fuse_request *r) {
    if (!r || atomic_fetch_sub_explicit(&r->references,1,memory_order_acq_rel)!=1) return;
    md_resource_wakeup_release(r->wakeup);
    md_fuse_connection_release(r->connection); free(r);
}
static void finish(struct md_fuse_request *r,int error) {
    if (r->done) return;
    r->error=error; r->done=1;
    md_resource_wakeup_signal(r->wakeup);
}
static void complete_abandoned(struct md_fuse_request *r) {
    pthread_mutex_lock(&r->connection->lock);
    void (*callback)(void *,int,const void *,size_t)=r->done ? r->abandoned : NULL;
    void *context=r->abandoned_context;
    if (callback) { r->abandoned=NULL; r->abandoned_context=NULL; }
    pthread_mutex_unlock(&r->connection->lock);
    if (callback) callback(context,r->error,r->data+r->input_size,r->reply_size);
}
/* Detach the transport reference. The caller releases it after unlocking. */
static void detach(struct md_fuse_connection *c,struct md_fuse_request *r) {
    struct md_fuse_request **p=&c->requests;
    while (*p!=r) p=&(*p)->next;
    *p=r->next; r->next=NULL;
    c->bytes-=r->input_size+r->capacity; --c->count;
}
static void dequeue(struct md_fuse_connection *c,struct md_fuse_request *r) {
    struct md_fuse_request **p=&c->first,*previous=NULL;
    while (*p!=r) { previous=*p; p=&(*p)->queue_next; }
    *p=r->queue_next;
    if (c->last==r) c->last=previous;
    r->queue_next=NULL; r->queued=0;
    if (!c->first && !c->error) md_event_acknowledge(c->event);
}
void md_fuse_connection_abort(struct md_fuse_connection *c,int error) {
    if (!c) return;
    if (error>=0 || error<-4095) error=-ECONNABORTED;
    pthread_mutex_lock(&c->lock);
    if (!c->error) c->error=error;
    struct md_fuse_request *list=c->requests;
    c->requests=c->first=c->last=c->offered=NULL; c->count=0; c->bytes=0;
    for (struct md_fuse_request *r=list;r;r=r->next) {
        r->queued=0; r->queue_next=NULL; finish(r,c->error);
    }
    md_event_signal(c->event);
    md_resource_wakeup_signal(c->reader);
    pthread_mutex_unlock(&c->lock);
    while (list) { struct md_fuse_request *r=list; list=r->next; r->next=NULL;
        complete_abandoned(r); md_fuse_request_release(r); }
}
int md_fuse_connection_error(struct md_fuse_connection *c) {
    pthread_mutex_lock(&c->lock); int error=c->error ? -ENOTCONN : 0;
    pthread_mutex_unlock(&c->lock); return error;
}
int md_fuse_connection_claim(struct md_fuse_connection *c) {
    pthread_mutex_lock(&c->lock);
    int error=c->error ? -ENOTCONN : c->claimed ? -EBUSY : 0;
    if (!error) c->claimed=1;
    pthread_mutex_unlock(&c->lock); return error;
}
int md_fuse_connection_descriptor(struct md_fuse_connection *c) { return c->event; }
void md_fuse_connection_reader(struct md_fuse_connection *c,struct md_resource_wakeup *reader) {
    pthread_mutex_lock(&c->lock);
    struct md_resource_wakeup *old=c->reader;
    c->reader=md_resource_wakeup_retain(reader);
    pthread_mutex_unlock(&c->lock); md_resource_wakeup_release(old);
}
long md_fuse_connection_poll(struct md_fuse_connection *c) {
    pthread_mutex_lock(&c->lock);
    long events=c->error ? POLLERR : POLLOUT|(c->first ? POLLIN : 0);
    pthread_mutex_unlock(&c->lock); return events;
}
void md_fuse_connection_notifications(struct md_fuse_connection *c,
        int (*notify)(void *,unsigned,const void *,size_t),void *context) {
    pthread_mutex_lock(&c->lock);
    c->notify=notify; c->notify_context=context;
    pthread_mutex_unlock(&c->lock);
}
int md_fuse_request_submit(struct md_fuse_connection *c,const struct fuse_in_header *header,
        const void *data,size_t size,size_t capacity,int no_reply,
        struct md_resource_wakeup *wakeup,struct md_fuse_request **out) {
    *out=NULL;
    if (!header || (size && !data) || size>MD_FUSE_PACKET_MAX-sizeof(*header)
            || capacity>MD_FUSE_PACKET_MAX-sizeof(struct fuse_out_header)) return -EINVAL;
    size_t input=sizeof(*header)+size;
    struct md_fuse_request *r=calloc(1,sizeof(*r)+input+capacity);
    if (!r) return -ENOMEM;
    atomic_init(&r->references,1);
    r->connection=md_fuse_connection_retain(c); r->wakeup=md_resource_wakeup_retain(wakeup);
    r->input_size=input; r->capacity=capacity; r->no_reply=no_reply;
    pthread_mutex_lock(&c->lock);
    /* Existing requests retain their abort result; new operations observe
     * a disconnected endpoint, as with Linux fuse_get_req(). */
    int error=c->error ? -ENOTCONN : 0;
    if (!error && (c->count==MD_FUSE_REQUEST_MAX || input+capacity>MD_FUSE_QUEUE_BYTES-c->bytes)) error=-EAGAIN;
    if (!error && c->sequence==UINT64_MAX) error=-EOVERFLOW;
    if (!error) {
        struct fuse_in_header h=*header; h.len=input; h.unique=++c->sequence;
        r->unique=h.unique; memcpy(r->data,&h,sizeof(h));
        if (size) memcpy(r->data+sizeof(h),data,size);
        atomic_fetch_add_explicit(&r->references,1,memory_order_relaxed);
        r->next=c->requests; c->requests=r; r->queued=1;
        if (c->last) c->last->queue_next=r; else c->first=r;
        c->last=r; ++c->count; c->bytes+=input+capacity;
        md_event_signal(c->event);
        md_resource_wakeup_signal(c->reader);
    }
    pthread_mutex_unlock(&c->lock);
    if (error) md_fuse_request_release(r); else *out=r;
    return error;
}
int md_fuse_request_ready(struct md_fuse_request *r) {
    pthread_mutex_lock(&r->connection->lock); int done=r->done;
    pthread_mutex_unlock(&r->connection->lock); return done;
}
long md_fuse_request_result(struct md_fuse_request *r,void *data,size_t capacity) {
    pthread_mutex_lock(&r->connection->lock);
    long result=!r->done ? -EINPROGRESS : r->error ? r->error :
        r->reply_size>capacity ? -ERANGE : r->reply_size && !data ? -EINVAL : (long)r->reply_size;
    if (result>0) memcpy(data,r->data+r->input_size,r->reply_size);
    pthread_mutex_unlock(&r->connection->lock); return result;
}
void md_fuse_request_cancel(struct md_fuse_request *r) {
    if (!r) return;
    struct md_fuse_connection *c=r->connection;
    pthread_mutex_lock(&c->lock);
    int retire=!r->done;
    if (retire) {
        finish(r,-ECANCELED);
        if (r->queued && c->offered!=r) dequeue(c,r);
        retire=!r->queued;
        if (retire) detach(c,r);
    }
    pthread_mutex_unlock(&c->lock);
    if (retire) md_fuse_request_release(r);
}
static void observe_completion(struct md_fuse_request *r,
        void (*callback)(void *,int,const void *,size_t),void *context,int cancel_queued) {
    struct md_fuse_connection *c=r->connection;
    atomic_fetch_add_explicit(&r->references,1,memory_order_relaxed);
    pthread_mutex_lock(&c->lock);
    r->abandoned=callback; r->abandoned_context=context;
    int retire=cancel_queued && !r->done && r->queued && c->offered!=r;
    if (retire) { finish(r,-ECANCELED); dequeue(c,r); detach(c,r); }
    pthread_mutex_unlock(&c->lock);
    complete_abandoned(r);
    if (retire) md_fuse_request_release(r);
    md_fuse_request_release(r);
}
void md_fuse_request_abandon(struct md_fuse_request *r,
        void (*callback)(void *,int,const void *,size_t),void *context) {
    observe_completion(r,callback,context,1);
}
void md_fuse_request_retire(struct md_fuse_request *r,
        void (*callback)(void *,int,const void *,size_t),void *context) {
    observe_completion(r,callback,context,0);
}
long md_fuse_connection_read(struct md_fuse_connection *c,void *data,size_t size) {
    pthread_mutex_lock(&c->lock);
    struct md_fuse_request *r=c->first;
    long result=c->error ? -ENODEV : !r ? -EAGAIN :
        size<r->input_size ? -EINVAL : !data ? -EFAULT : (long)r->input_size;
    if (result>0) { memcpy(data,r->data,r->input_size); c->offered=r; }
    pthread_mutex_unlock(&c->lock); return result;
}
void md_fuse_connection_read_commit(struct md_fuse_connection *c,size_t size) {
    pthread_mutex_lock(&c->lock);
    struct md_fuse_request *r=c->offered;
    int retire=0;
    if (r && size && size<=r->input_size) {
        c->offered=NULL; dequeue(c,r);
        if (size<r->input_size) finish(r,-EIO);
        if (r->no_reply) finish(r,0);
        if (r->done) { detach(c,r); retire=1; }
    }
    pthread_mutex_unlock(&c->lock);
    if (retire) { complete_abandoned(r); md_fuse_request_release(r); }
}
long md_fuse_connection_write(struct md_fuse_connection *c,const void *data,size_t size) {
    if (!data || size<sizeof(struct fuse_out_header) || size>MD_FUSE_PACKET_MAX) return -EINVAL;
    struct fuse_out_header h; memcpy(&h,data,sizeof(h));
    if (h.len!=size) return -EINVAL;
    if (!h.unique) {
        pthread_mutex_lock(&c->lock);
        int error=c->error ? -ENODEV : h.error<=0 ? -EINVAL : !c->notify ? -EOPNOTSUPP
            : c->notify(c->notify_context,h.error,(const char *)data+sizeof(h),size-sizeof(h));
        pthread_mutex_unlock(&c->lock);
        return error ? error : (long)size;
    }
    if (h.error>0 || h.error<-4095 || (h.error && size!=sizeof(h))) return -EINVAL;
    pthread_mutex_lock(&c->lock);
    struct md_fuse_request *r=c->requests;
    while (r && r->unique!=h.unique) r=r->next;
    int error=c->error ? -ENOENT : !r || r->done || r->no_reply ? -ENOENT :
        r->queued && c->offered!=r ? -EINVAL : size-sizeof(h)>r->capacity ? -EINVAL : 0;
    int retire=0;
    if (!error) {
        r->reply_size=size-sizeof(h);
        if (r->reply_size) memcpy(r->data+r->input_size,(const char *)data+sizeof(h),r->reply_size);
        finish(r,h.error);
        if (!r->queued) { detach(c,r); retire=1; }
        atomic_fetch_add_explicit(&r->references,1,memory_order_relaxed);
    }
    pthread_mutex_unlock(&c->lock);
    if (!error) { complete_abandoned(r); md_fuse_request_release(r); }
    if (retire) md_fuse_request_release(r);
    return error ? error : (long)size;
}
