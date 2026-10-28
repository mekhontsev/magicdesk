#define _GNU_SOURCE
#include "shm_bridge.h"
#include "ipc_namespace.h"
#include "task_registry.h"
#include "fs_wire.h"
#include "resource_protocol.h"
#include "pid_namespace.h"
#include <errno.h>
#include <stdlib.h>
#include <sys/epoll.h>
#include <sys/socket.h>
#include <unistd.h>

enum { CHANNELS=1024 };
struct channel {
    struct channel *next;
    int fd, pending;
    struct md_identity identity;
    struct md_ipc_namespace *namespace;
    struct md_shm *shm;
    struct md_pid_identity *process;
    pid_t pid;
    struct lorie_resource_reply reply;
    int rights[2];
};
struct md_shm_bridge { int epoll; unsigned count; struct channel *channels; };
static void close_rights(struct channel *c) {
    for (unsigned i=0;i<2;++i) { if (c->rights[i]>=0) close(c->rights[i]); c->rights[i]=-1; }
}
static void retire(struct md_shm_bridge *b,struct channel *c) {
    epoll_ctl(b->epoll,EPOLL_CTL_DEL,c->fd,NULL); close(c->fd);
    close_rights(c); md_shm_close(c->shm);
    md_pid_identity_release(c->process);
    md_identity_release(&c->identity); md_ipc_namespace_release(c->namespace);
    struct channel **p=&b->channels;
    while (*p!=c) p=&(*p)->next;
    *p=c->next; --b->count; free(c);
}
int md_shm_bridge_connect(struct md_shm_bridge **out,const struct md_task_snapshot *task) {
    if (!task || !task->ipc) return -ENOTSUP;
    struct md_shm_bridge *b=*out;
    if (!b) {
        b=calloc(1,sizeof(*b));
        if (!b) return -ENOMEM;
        b->epoll=epoll_create1(EPOLL_CLOEXEC);
        if (b->epoll<0) { int error=-errno; free(b); return error; }
        *out=b;
    }
    if (b->count==CHANNELS) return -ENOSPC;
    struct channel *c=calloc(1,sizeof(*c));
    if (!c) return -ENOMEM;
    int pair[2];
    if (socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC|SOCK_NONBLOCK,0,pair)) {
        int error=-errno; free(c); return error;
    }
    c->fd=pair[0]; c->rights[0]=c->rights[1]=-1;
    struct epoll_event event={.events=EPOLLIN,.data.ptr=c};
    if (epoll_ctl(b->epoll,EPOLL_CTL_ADD,c->fd,&event)) {
        int error=-errno; close(pair[0]); close(pair[1]); free(c); return error;
    }
    c->identity=md_identity_copy(&task->identity);
    c->namespace=md_ipc_namespace_retain(task->ipc); c->process=md_pid_identity_retain(task->pid_group); c->pid=task->tgid;
    c->next=b->channels; b->channels=c; ++b->count;
    return pair[1];
}
int md_shm_bridge_pollfd(const struct md_shm_bridge *b) { return b ? b->epoll : -1; }
static int dispatch(struct md_shm_bridge *b,struct channel *c) {
    if (!c->pending) {
        struct lorie_resource_request q;
        struct md_fs_rights rights={0};
        long n=md_fs_receive(c->fd,&q,sizeof(q),&rights);
        unsigned count=rights.count; md_fs_close_rights(&rights);
        if (n==-EAGAIN || n==-EINTR) return 0;
        if (n!=sizeof(q) || count || q.magic!=LORIE_RESOURCE_MAGIC) return -EPROTO;
        int error=q.operation!=LORIE_RESOURCE_SHM ? -EOPNOTSUPP
            : q.flags&~LORIE_RESOURCE_READONLY ? -EINVAL : 0;
        if (!error && !c->shm)
            error=md_ipc_namespace_open_memory(c->namespace,&c->shm);
        c->reply=(struct lorie_resource_reply){0};
        if (!error) error=md_shm_export(c->shm,&c->identity,c->pid,c->process,(int)q.id,
            !!(q.flags&LORIE_RESOURCE_READONLY),c->rights,&c->reply.size);
        c->reply.error=error; c->pending=1;
    }
    long n=md_fs_send_fds(c->fd,&c->reply,sizeof(c->reply),c->rights,c->reply.error ? 0 : 2);
    if (n==-EINTR || n==-EAGAIN) {
        struct epoll_event event={.events=EPOLLOUT,.data.ptr=c};
        return epoll_ctl(b->epoll,EPOLL_CTL_MOD,c->fd,&event) ? -errno : 0;
    }
    close_rights(c);
    if (n!=sizeof(c->reply)) return -EPIPE;
    c->pending=0;
    struct epoll_event event={.events=EPOLLIN,.data.ptr=c};
    return epoll_ctl(b->epoll,EPOLL_CTL_MOD,c->fd,&event) ? -errno : 0;
}
int md_shm_bridge_dispatch(struct md_shm_bridge *b) {
    if (!b) return 0;
    struct epoll_event events[32];
    int n=epoll_wait(b->epoll,events,32,0);
    if (n<0) return errno==EINTR ? 0 : -errno;
    for (int i=0;i<n;++i) {
        struct channel *c=events[i].data.ptr;
        if (dispatch(b,c) || (events[i].events&(EPOLLHUP|EPOLLERR))) retire(b,c);
    }
    return 0;
}
void md_shm_bridge_close(struct md_shm_bridge *b) {
    if (!b) return;
    while (b->channels) retire(b,b->channels);
    close(b->epoll); free(b);
}
