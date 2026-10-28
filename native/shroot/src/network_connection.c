#define _GNU_SOURCE
#include "network_connection.h"
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <unistd.h>

enum { NATIVE, PENDING, COMPLETE, FAILED };
struct md_network_connection {
    pthread_mutex_t lock;
    int query,wake,completion,state,result,error;
};
void md_network_connection_free(struct md_network_connection *c) {
    if (!c) return;
    if (c->query>=0) close(c->query);
    if (c->wake>=0) close(c->wake);
    if (c->completion>=0) close(c->completion);
    pthread_mutex_destroy(&c->lock); free(c);
}
struct md_network_connection *md_network_connection_new(int fd) {
    struct md_network_connection *c=calloc(1,sizeof(*c));
    if (!c) return NULL;
    c->query=c->wake=c->completion=-1;
    int error=pthread_mutex_init(&c->lock,NULL);
    if (error) { free(c); errno=error; return NULL; }
    c->query=epoll_create1(EPOLL_CLOEXEC);
    c->wake=epoll_create1(EPOLL_CLOEXEC);
    if (c->query<0 || c->wake<0) error=errno;
    struct epoll_event e={.events=EPOLLIN|EPOLLOUT|EPOLLPRI|EPOLLRDHUP|EPOLLRDNORM|EPOLLWRNORM};
    if (!error && epoll_ctl(c->query,EPOLL_CTL_ADD,fd,&e)) error=errno;
    e.events|=EPOLLET;
    if (!error && epoll_ctl(c->wake,EPOLL_CTL_ADD,fd,&e)) error=errno;
    if (error) { md_network_connection_free(c); errno=error; return NULL; }
    return c;
}
int md_network_connection_begin(struct md_network_connection *c) {
    if (!c) return 0;
    pthread_mutex_lock(&c->lock);
    int error=0;
    if (c->state==PENDING) error=-EALREADY;
    else if (c->state==COMPLETE) error=-EISCONN;
    if (!error && c->completion<0) {
        int fd=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
        struct epoll_event e={.events=EPOLLIN,.data.u64=1};
        if (fd<0) error=-errno;
        else if (epoll_ctl(c->wake,EPOLL_CTL_ADD,fd,&e)) { error=-errno; close(fd); }
        else c->completion=fd;
    }
    if (!error) {
        uint64_t value;
        while (read(c->completion,&value,sizeof(value))>0) {}
        c->state=PENDING; c->result=c->error=0;
    }
    pthread_mutex_unlock(&c->lock); return error;
}
void md_network_connection_complete(struct md_network_connection *c,int result) {
    if (!c) return;
    pthread_mutex_lock(&c->lock);
    if (c->state==PENDING) {
        c->result=result; c->error=result<0 ? -result : 0;
        c->state=result<0 ? FAILED : COMPLETE;
        uint64_t one=1;
        ssize_t written=write(c->completion,&one,sizeof(one)); (void)written;
    }
    pthread_mutex_unlock(&c->lock);
}
void md_network_connection_reset(struct md_network_connection *c) {
    if (!c) return;
    pthread_mutex_lock(&c->lock);
    c->state=NATIVE; c->result=c->error=0;
    pthread_mutex_unlock(&c->lock);
}
int md_network_connection_result(struct md_network_connection *c,int *wait) {
    if (wait) *wait=-1;
    if (!c) return 0;
    pthread_mutex_lock(&c->lock);
    int result=c->state==PENDING ? -EINPROGRESS : c->result;
    if (wait && c->state==PENDING) *wait=c->completion;
    pthread_mutex_unlock(&c->lock); return result;
}
int md_network_connection_error(struct md_network_connection *c,int *value) {
    if (!c) return 0;
    pthread_mutex_lock(&c->lock);
    int failed=c->state==FAILED;
    if (failed) { *value=c->error; c->error=0; }
    pthread_mutex_unlock(&c->lock); return failed;
}
static int poll_descriptor(void *context,int *fd) {
    *fd=((struct md_network_connection *)context)->wake; return 0;
}
static long poll_value(void *context) {
    struct md_network_connection *c=context;
    pthread_mutex_lock(&c->lock);
    /* Never consume the shared wake queue: each enclosing epoll needs its own
     * notification. Only the separate level query supplies the current mask. */
    struct epoll_event e;
    long result=0;
    if (c->state==FAILED)
        result=EPOLLIN|EPOLLOUT|EPOLLRDNORM|EPOLLWRNORM|EPOLLHUP|(c->error ? EPOLLERR : 0);
    else if (c->state!=PENDING) {
        int count=epoll_wait(c->query,&e,1,0);
        result=count<0 ? -errno : count ? e.events : 0;
    }
    pthread_mutex_unlock(&c->lock); return result;
}
static int poll_native(void *context) {
    struct md_network_connection *c=context;
    pthread_mutex_lock(&c->lock);
    int native=c->state==NATIVE || c->state==COMPLETE;
    pthread_mutex_unlock(&c->lock); return native;
}
static int poll_wait_descriptor(void *context,int *fd) {
    struct md_network_connection *c=context;
    pthread_mutex_lock(&c->lock); *fd=c->completion;
    pthread_mutex_unlock(&c->lock); return 0;
}
static const struct md_control_file_ops operations={.poll_descriptor=poll_descriptor,.poll=poll_value,
    .poll_native=poll_native,.poll_wait_descriptor=poll_wait_descriptor};
struct md_control_file md_network_connection_readiness(struct md_network_connection *c) {
    return c ? (struct md_control_file){&operations,c} : (struct md_control_file){0};
}
