#define _GNU_SOURCE
#include "fuse_transport.h"
#include "resource_order.h"
#include <assert.h>
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <sys/eventfd.h>
#include <unistd.h>

static struct md_fuse_request *submit(struct md_fuse_connection *c,int no_reply,struct md_resource_wakeup *wake) {
    struct fuse_in_header h={.opcode=no_reply ? FUSE_FORGET : FUSE_LOOKUP,.nodeid=1,.uid=1000,.gid=1000,.pid=42};
    struct md_fuse_request *r=NULL;
    assert(!md_fuse_request_submit(c,&h,"file",5,32,no_reply,wake,&r));
    return r;
}
static uint64_t receive(struct md_fuse_connection *c,int commit) {
    unsigned char data[256];
    long size=md_fuse_connection_read(c,data,sizeof(data));
    assert(size==sizeof(struct fuse_in_header)+5);
    struct fuse_in_header h; memcpy(&h,data,sizeof(h));
    assert(h.len==size && h.unique && h.uid==1000 && h.gid==1000 && h.pid==42 && h.nodeid==1);
    assert(!strcmp((char *)data+sizeof(h),"file"));
    if (commit) md_fuse_connection_read_commit(c,size);
    return h.unique;
}
static long reply(struct md_fuse_connection *c,uint64_t unique,int error) {
    struct { struct fuse_out_header h; char data[8]; } p={.h={.unique=unique,.error=error}};
    memcpy(p.data,"reply",6); p.h.len=error ? sizeof(p.h) : sizeof(p);
    return md_fuse_connection_write(c,&p,p.h.len);
}
static void dispose(struct md_fuse_request *r) { md_fuse_request_cancel(r); md_fuse_request_release(r); }
static void basics(void) {
    struct md_fuse_connection *c=NULL; assert(!md_fuse_connection_new(&c));
    assert(!md_fuse_connection_error(c));
    int fd=eventfd(0,EFD_NONBLOCK|EFD_CLOEXEC); assert(fd>=0);
    struct md_resource_wakeup *wake=NULL; assert(!md_resource_wakeup_create(fd,&wake));
    char data[256];
    assert(md_fuse_connection_read(c,data,sizeof(data))==-EAGAIN);
    assert(md_fuse_connection_poll(c)==POLLOUT);
    struct md_fuse_request *a=submit(c,0,wake),*b=submit(c,0,wake);
    assert(md_fuse_connection_poll(c)==(POLLIN|POLLOUT));
    assert(md_fuse_request_result(a,data,sizeof(data))==-EINPROGRESS);
    assert(md_fuse_connection_read(c,data,1)==-EINVAL);
    uint64_t id=receive(c,0);
    assert(receive(c,0)==id); /* Failed guest copy does not consume a message. */
    assert(reply(c,id,0)>0); /* Reply before the read's acknowledgement. */
    assert(reply(c,id,0)==-ENOENT);
    assert(md_fuse_request_result(a,data,1)==-ERANGE);
    assert(md_fuse_request_result(a,data,sizeof(data))==8 && !strcmp(data,"reply"));
    assert(receive(c,1)==id);
    uint64_t second=receive(c,1); assert(second!=id);
    assert(reply(c,second,-EACCES)>0);
    assert(md_fuse_request_result(b,NULL,0)==-EACCES);
    struct pollfd poller={fd,POLLIN,0}; assert(poll(&poller,1,0)==1);
    assert(md_fuse_connection_poll(c)==POLLOUT);
    dispose(a); dispose(b);
    struct md_fuse_request *n=submit(c,1,wake);
    id=receive(c,1); assert(md_fuse_request_result(n,NULL,0)==0);
    assert(reply(c,id,0)==-ENOENT); dispose(n);
    struct fuse_out_header bad={.len=sizeof(bad),.unique=id,.error=1};
    assert(md_fuse_connection_write(c,&bad,sizeof(bad))==-EINVAL);
    bad.error=-4096; assert(md_fuse_connection_write(c,&bad,sizeof(bad))==-EINVAL);
    bad.error=0; bad.len++; assert(md_fuse_connection_write(c,&bad,sizeof(bad))==-EINVAL);
    bad.len=sizeof(bad); bad.unique=0; bad.error=FUSE_NOTIFY_INVAL_INODE;
    assert(md_fuse_connection_write(c,&bad,sizeof(bad))==-EOPNOTSUPP);
    md_fuse_connection_abort(c,-ECONNABORTED);
    assert(md_fuse_connection_read(c,data,sizeof(data))==-ENODEV);
    assert(md_fuse_connection_poll(c)==POLLERR);
    assert(md_fuse_connection_error(c)==-ENOTCONN);
    assert(md_fuse_connection_claim(c)==-ENOTCONN);
    struct fuse_in_header h={.opcode=FUSE_LOOKUP};
    assert(md_fuse_request_submit(c,&h,NULL,0,0,0,NULL,&a)==-ENOTCONN && !a);
    md_resource_wakeup_release(wake); close(fd); md_fuse_connection_release(c);
}
static void cancellation(void) {
    struct md_fuse_connection *c=NULL; assert(!md_fuse_connection_new(&c));
    struct md_fuse_request *a=submit(c,0,NULL),*b=submit(c,0,NULL);
    dispose(a); uint64_t id=receive(c,1); dispose(b);
    assert(reply(c,id,0)==-ENOENT);
    a=submit(c,0,NULL); b=submit(c,0,NULL);
    id=receive(c,0); dispose(a);
    assert(reply(c,id,0)==-ENOENT);
    assert(receive(c,1)==id); /* Cancel cannot replace a packet already offered. */
    id=receive(c,1); assert(reply(c,id,0)>0); dispose(b);
    a=submit(c,0,NULL); b=submit(c,0,NULL); receive(c,0);
    md_fuse_connection_abort(c,-ECONNABORTED);
    md_fuse_connection_read_commit(c,sizeof(struct fuse_in_header)+5);
    assert(md_fuse_request_result(a,NULL,0)==-ECONNABORTED);
    assert(md_fuse_request_result(b,NULL,0)==-ECONNABORTED);
    assert(md_fuse_connection_error(c)==-ENOTCONN);
    struct fuse_in_header h={.opcode=FUSE_LOOKUP};
    struct md_fuse_request *late=NULL;
    assert(md_fuse_request_submit(c,&h,NULL,0,0,0,NULL,&late)==-ENOTCONN && !late);
    md_fuse_connection_release(c); /* Requests retain the disconnected owner. */
    dispose(a); dispose(b);
}
struct abandoned { unsigned calls; int error; size_t size; };
static void abandoned_reply(void *context,int error,const void *bytes,size_t size) {
    struct abandoned *result=context;
    ++result->calls; result->error=error; result->size=size;
    if (!error) assert(size==8 && !strcmp(bytes,"reply"));
}
static void late_reply(void) {
    struct md_fuse_connection *c=NULL; assert(!md_fuse_connection_new(&c));
    struct abandoned result={0};
    struct md_fuse_request *r=submit(c,0,NULL);
    md_fuse_request_abandon(r,abandoned_reply,&result); md_fuse_request_release(r);
    assert(result.calls==1 && result.error==-ECANCELED);
    r=submit(c,0,NULL); uint64_t id=receive(c,1);
    md_fuse_request_abandon(r,abandoned_reply,&result); md_fuse_request_release(r);
    assert(result.calls==1); assert(reply(c,id,0)>0);
    assert(result.calls==2 && !result.error && result.size==8);
    assert(reply(c,id,0)==-ENOENT);
    r=submit(c,0,NULL); id=receive(c,0); assert(reply(c,id,0)>0);
    md_fuse_request_abandon(r,abandoned_reply,&result); md_fuse_request_release(r);
    assert(result.calls==3 && !result.error);
    assert(receive(c,1)==id && result.calls==3);
    r=submit(c,0,NULL); receive(c,1);
    md_fuse_request_abandon(r,abandoned_reply,&result); md_fuse_request_release(r);
    md_fuse_connection_abort(c,-ECONNABORTED);
    assert(result.calls==4 && result.error==-ECONNABORTED);
    md_fuse_connection_release(c);
}
static void terminal_reply(void) {
    for (unsigned state=0;state<4;++state) {
        struct md_fuse_connection *c=NULL; assert(!md_fuse_connection_new(&c));
        struct abandoned result={0};
        struct md_fuse_request *r=submit(c,0,NULL);
        uint64_t id=state ? receive(c,1) : 0;
        if (state==2) assert(reply(c,id,0)>0);
        md_fuse_request_retire(r,abandoned_reply,&result); md_fuse_request_release(r);
        assert(result.calls==(state==2));
        if (!state) id=receive(c,1); /* Caller exit does not cancel queued terminal work. */
        if (state==3) md_fuse_connection_abort(c,-ENODEV);
        else if (state!=2) assert(reply(c,id,0)>0);
        assert(result.calls==1 && result.error==(state==3 ? -ENODEV : 0));
        if (state!=3) assert(reply(c,id,0)==-ENOENT);
        md_fuse_connection_abort(c,-ENODEV);
        assert(result.calls==1); md_fuse_connection_release(c);
    }
}
static void bounds(void) {
    struct md_fuse_connection *c=NULL; assert(!md_fuse_connection_new(&c));
    struct md_fuse_request *r[MD_FUSE_REQUEST_MAX],*extra=NULL;
    for (unsigned i=0;i<MD_FUSE_REQUEST_MAX;++i) r[i]=submit(c,0,NULL);
    struct fuse_in_header h={.opcode=FUSE_LOOKUP};
    assert(md_fuse_request_submit(c,&h,NULL,0,0,0,NULL,&extra)==-EAGAIN && !extra);
    for (unsigned i=0;i<MD_FUSE_REQUEST_MAX;++i) dispose(r[i]);
    extra=submit(c,0,NULL); dispose(extra);
    unsigned count=0;
    while (count<MD_FUSE_REQUEST_MAX && !md_fuse_request_submit(c,&h,NULL,0,
            MD_FUSE_PACKET_MAX-sizeof(struct fuse_out_header),0,NULL,&r[count])) ++count;
    assert(count>0 && count<MD_FUSE_REQUEST_MAX);
    for (unsigned i=0;i<count;++i) dispose(r[i]);
    md_fuse_connection_abort(c,-ENODEV); md_fuse_connection_release(c);
}
struct daemon_context { struct md_fuse_connection *connection; unsigned count; };
static void *daemon_main(void *context) {
    struct daemon_context *d=context;
    for (unsigned i=0;i<d->count;++i) {
        struct pollfd p={md_fuse_connection_descriptor(d->connection),POLLIN,0};
        /* EVENT_WAIT: queued daemon request; timeout fails the fixture. */
        assert(poll(&p,1,2000)==1);
        assert(reply(d->connection,receive(d->connection,1),0)>0);
    }
    return NULL;
}
static void concurrency(void) {
    struct md_fuse_connection *c=NULL,*stuck=NULL;
    assert(!md_fuse_connection_new(&c) && !md_fuse_connection_new(&stuck));
    struct md_fuse_request *blocked=submit(stuck,0,NULL);
    int fd=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK); assert(fd>=0);
    struct md_resource_wakeup *wake=NULL; assert(!md_resource_wakeup_create(fd,&wake));
    struct daemon_context d={c,1000}; pthread_t thread;
    assert(!pthread_create(&thread,NULL,daemon_main,&d));
    for (unsigned i=0;i<d.count;++i) {
        struct md_fuse_request *r=submit(c,0,wake);
        while (!md_fuse_request_ready(r)) {
            struct pollfd p={fd,POLLIN,0};
            /* EVENT_WAIT: exact request completion; timeout fails the fixture. */
            assert(poll(&p,1,2000)==1);
            uint64_t count; assert(read(fd,&count,sizeof(count))==sizeof(count));
        }
        char data[8]; assert(md_fuse_request_result(r,data,sizeof(data))==sizeof(data));
        dispose(r);
    }
    assert(!pthread_join(thread,NULL));
    assert(!md_fuse_request_ready(blocked));
    md_fuse_connection_abort(stuck,-ECONNABORTED);
    assert(md_fuse_request_result(blocked,NULL,0)==-ECONNABORTED); dispose(blocked);
    md_fuse_connection_abort(c,-ECONNABORTED);
    md_fuse_connection_release(c); md_fuse_connection_release(stuck);
    md_resource_wakeup_release(wake); close(fd);
}
int main(void) {
    basics(); cancellation(); late_reply(); terminal_reply(); bounds(); concurrency();
    puts("FUSE transport: replies, copy acknowledgement, cancellation, bounds, disconnect and concurrency OK");
    return 0;
}
