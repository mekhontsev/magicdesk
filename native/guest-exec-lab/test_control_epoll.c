#define _GNU_SOURCE
#include "control_epoll.h"
#include "control_event.h"
#include "control_subscription.h"
#include "event_signal.h"
#include "fd_weak.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/eventfd.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <unistd.h>

extern void control_no_allocation(int);
extern void control_fail_allocation(unsigned);
struct sample {
    struct md_fd_object identity;
    struct md_control_cursor cursor;
    uint32_t events;
    int error,level;
};
static void destroy(struct md_fd_object *object) {
    struct sample *s=(void *)object;
    md_control_cursor_close(&s->cursor); free(s);
}
static const struct md_fd_object_type type={.destroy=destroy};
static void *retain(void *s) { return md_fd_object_retain(s); }
static void release(void *s) { md_fd_object_release(s); }
static int wake(void *context,int *descriptor) {
    struct sample *s=context;
    int fd=md_control_cursor_watch(&s->cursor);
    if (fd<0) return fd;
    *descriptor=fd; return 0;
}
static long sample_poll(void *context) {
    struct sample *s=context;
    if (s->error) return s->error;
    if (s->level) return s->events;
    int changed=md_control_cursor_poll(&s->cursor);
    if (changed<0) return changed;
    return s->events|(changed ? EPOLLPRI|EPOLLERR : 0);
}
static const struct md_control_file_ops ops={.retain=retain,.release=release,
    .poll_descriptor=wake,.poll=sample_poll};
static struct sample *sample(struct md_control_event *source,uint32_t events) {
    struct sample *s=calloc(1,sizeof(*s)); assert(s);
    md_fd_object_init(&s->identity,&type);
    md_control_cursor_open(&s->cursor,source); s->events=events; return s;
}
static int ctl(struct md_control_epoll *q,int op,int fd,struct sample *s,uint32_t events,uint64_t data) {
    struct epoll_event e={.events=events,.data.u64=data};
    return md_control_epoll_ctl(q,op,fd,&s->identity,(struct md_control_file){&ops,s},&e,-1);
}
static unsigned descriptors(void) {
    DIR *d=opendir("/proc/self/fd"); assert(d);
    unsigned n=0;
    for (struct dirent *e;(e=readdir(d));) if (e->d_name[0]!='.') ++n;
    closedir(d); return n;
}
static void empty(struct md_control_epoll *q) {
    struct epoll_event e;
    assert(!md_control_epoll_collect(q,&e,1));
    struct pollfd p={md_control_epoll_descriptor(q),POLLIN,0};
    assert(!poll(&p,1,0));
}
static void identities(void) {
    unsigned before=descriptors();
    struct md_control_event *source=md_control_event_new(); assert(source);
    struct sample *a=sample(source,0),*b=sample(source,0);
    struct md_control_epoll *q=md_control_epoll_new(); assert(q);
    /* Duplicate application data, reused descriptor number, distinct OFDs. */
    assert(!ctl(q,EPOLL_CTL_ADD,9,a,0,UINT64_MAX));
    assert(!ctl(q,EPOLL_CTL_ADD,9,b,0,UINT64_MAX));
    assert(ctl(q,EPOLL_CTL_ADD,9,a,0,0)==-EEXIST);
    assert(ctl(q,EPOLL_CTL_MOD,10,a,0,0)==-ENOENT);
    empty(q);
    assert(!md_control_event_publish(source));
    struct epoll_event out[4];
    assert(md_control_epoll_collect(q,out,4)==2);
    for (unsigned i=0;i<2;++i) assert(out[i].events==EPOLLERR && out[i].data.u64==UINT64_MAX);
    empty(q);
    assert(!ctl(q,EPOLL_CTL_ADD,10,a,0,99));
    assert(!md_control_event_publish(source));
    int n=md_control_epoll_collect(q,out,4);
    /* The two registrations of a share consumption, unlike independent b. */
    assert(n==2);
    assert(!ctl(q,EPOLL_CTL_DEL,9,a,0,0));
    assert(!md_control_event_publish(source));
    assert(md_control_epoll_collect(q,out,4)==2);
    assert((out[0].data.u64==99 || out[1].data.u64==99));
    release(a); a=NULL;
    assert(!md_control_event_publish(source));
    assert(md_control_epoll_collect(q,out,4)==1 && out[0].data.u64==UINT64_MAX);
    release(b); b=NULL; empty(q);
    md_control_epoll_release(q); release(a); release(b); md_control_event_release(source);
    assert(descriptors()==before);
    puts("PASS virtual epoll identity: duplicate data, dup consumption, FD reuse and final retirement");
}
static void policies(void) {
    struct md_control_event *source=md_control_event_new(); assert(source);
    struct sample *a=sample(source,EPOLLIN),*b=sample(source,EPOLLIN);
    struct md_control_epoll *q=md_control_epoll_new(); assert(q);
    assert(!ctl(q,EPOLL_CTL_ADD,1,a,EPOLLIN,1));
    assert(!ctl(q,EPOLL_CTL_ADD,2,b,EPOLLIN,2));
    struct epoll_event out[2];
    control_no_allocation(1);
    for (unsigned i=0;i<10000;++i) {
        assert(md_control_epoll_collect(q,out,1)==1 && out[0].data.u64==1);
        assert(md_control_epoll_collect(q,out,1)==1 && out[0].data.u64==2);
    }
    control_no_allocation(0);
    assert(!ctl(q,EPOLL_CTL_MOD,1,a,EPOLLIN|EPOLLONESHOT,3));
    assert(!ctl(q,EPOLL_CTL_MOD,2,b,EPOLLIN|EPOLLET,4));
    assert(md_control_epoll_collect(q,out,2)==2); empty(q);
    assert(!md_control_event_publish(source));
    assert(md_control_epoll_collect(q,out,2)==1 && out[0].data.u64==4);
    empty(q);
    assert(!ctl(q,EPOLL_CTL_MOD,1,a,EPOLLIN|EPOLLONESHOT,5));
    assert(md_control_epoll_collect(q,out,2)==1 && out[0].data.u64==5); empty(q);
    assert(ctl(q,EPOLL_CTL_MOD,2,b,EPOLLIN|EPOLLEXCLUSIVE,6)==-EINVAL);
    assert(ctl(q,EPOLL_CTL_ADD,3,b,EPOLLWAKEUP,6)==-EOPNOTSUPP);
    assert(!md_control_event_publish(source));
    assert(md_control_epoll_collect(q,out,2)==1 && out[0].data.u64==4);
    a->error=-EIO;
    assert(ctl(q,EPOLL_CTL_MOD,1,a,EPOLLIN,7)==-EIO); a->error=0;
    assert(!md_control_event_publish(source));
    assert(md_control_epoll_collect(q,out,2)==1 && out[0].data.u64==4);
    md_control_epoll_release(q); release(a); release(b); md_control_event_release(source);
    puts("PASS virtual epoll level/edge/oneshot, fair bounded results and failed rearm");
}
static void mixed(void) {
    unsigned before=descriptors();
    int native=epoll_create1(EPOLL_CLOEXEC),counter=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
    assert(native>=0 && counter>=0);
    /* Existing kernel registrations are never imported or reidentified by data. */
    struct epoll_event e={.events=EPOLLIN,.data.u64=0},out[2];
    assert(!epoll_ctl(native,EPOLL_CTL_ADD,counter,&e));
    struct md_control_event *source=md_control_event_new(); assert(source);
    struct sample *s=sample(source,0);
    struct md_control_epoll *q=md_control_epoll_new(); assert(q);
    assert(!ctl(q,EPOLL_CTL_ADD,9,s,0,0));
    struct pollfd waiters[2]={{native,POLLIN,0},{md_control_epoll_descriptor(q),POLLIN,0}};
    control_no_allocation(1);
    for (unsigned i=0;i<100000;++i) {
        assert(!md_event_signal(counter));
        assert(!md_control_event_publish(source));
        assert(poll(waiters,2,0)==2);
        assert(epoll_wait(native,out,2,0)==1 && out[0].data.u64==0 && out[0].events==EPOLLIN);
        assert(md_control_epoll_collect(q,out+1,1)==1
            && out[1].data.u64==0 && out[1].events==EPOLLERR);
        assert(!md_event_acknowledge(counter));
        assert(!md_control_epoll_collect(q,out,2));
        assert(!poll(waiters,2,0));
    }
    control_no_allocation(0);
    md_control_epoll_release(q); release(s); md_control_event_release(source);
    close(native); close(counter);
    assert(descriptors()==before);
    puts("PASS mixed native/virtual epoll: existing equal data, 100000 rounds without allocation or FD leak");
}
static void exclusive(void) {
    unsigned before=descriptors();
    struct md_control_event *source=md_control_event_new(); assert(source);
    struct sample *s=sample(source,0);
    s->level=1;
    struct md_control_epoll *q[3];
    for (unsigned i=0;i<3;++i) { q[i]=md_control_epoll_new(); assert(q[i]); }
    const uint32_t flags=EPOLLIN|EPOLLEXCLUSIVE;
    assert(ctl(q[0],EPOLL_CTL_ADD,9,s,flags|EPOLLONESHOT,9)==-EINVAL);
    assert(ctl(q[0],EPOLL_CTL_ADD,9,s,flags|EPOLLPRI,9)==-EINVAL);
    assert(ctl(q[0],EPOLL_CTL_ADD,9,s,flags|EPOLLRDHUP,9)==-EINVAL);
    assert(ctl(q[0],EPOLL_CTL_MOD,9,s,flags,9)==-EINVAL);
    for (unsigned i=0;i<3;++i) {
        assert(!ctl(q[i],EPOLL_CTL_ADD,9,s,EPOLLIN|EPOLLET|(i ? EPOLLEXCLUSIVE : 0),i));
        empty(q[i]);
    }
    assert(ctl(q[1],EPOLL_CTL_ADD,9,s,flags,9)==-EEXIST);
    assert(ctl(q[1],EPOLL_CTL_MOD,9,s,EPOLLIN,9)==-EINVAL);
    for (unsigned round=0;round<32;++round) {
        s->events=EPOLLIN; assert(!md_control_event_publish(source));
        unsigned exclusive_ready=0;
        for (unsigned i=0;i<3;++i) {
            struct epoll_event out;
            int count=md_control_epoll_collect(q[i],&out,1);
            assert(count>=0 && (i || count==1));
            if (count) {
                assert(out.data.u64==i && (out.events&EPOLLIN));
                assert(!(out.events&(EPOLLEXCLUSIVE|EPOLLET)));
                if (i) ++exclusive_ready;
            }
        }
        assert(exclusive_ready);
        s->events=0;
        assert(md_control_cursor_poll(&s->cursor)==1);
        for (unsigned i=0;i<3;++i) empty(q[i]);
    }
    assert(!ctl(q[1],EPOLL_CTL_DEL,9,s,UINT32_MAX,0));
    assert(!ctl(q[1],EPOLL_CTL_ADD,9,s,EPOLLIN,10));
    assert(!ctl(q[1],EPOLL_CTL_MOD,9,s,EPOLLIN|EPOLLONESHOT,11));
    release(s);
    for (unsigned i=0;i<3;++i) { empty(q[i]); md_control_epoll_release(q[i]); }
    md_control_event_release(source);
    assert(descriptors()==before);
    puts("PASS exclusive epoll: masks, ADD/MOD/DEL, mixed observers, data and retirement");
}
static void subscription_lifetime(void) {
    unsigned before=descriptors();
    struct md_control_event *source=md_control_event_new(); assert(source);
    struct sample *s=sample(source,0);
    struct md_control_file provider={&ops,s},subscription={0};
    int sockets[2]; assert(!socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,sockets));
    unsigned acquired=descriptors();
    control_fail_allocation(1);
    assert(md_control_subscription(provider,sockets[0],-1,EPOLLIN|EPOLLEXCLUSIVE,&subscription)==-ENOMEM);
    control_fail_allocation(0);
    assert(descriptors()==acquired && !subscription.ops);
    assert(md_control_subscription(provider,sockets[0],-1,EPOLLPRI|EPOLLEXCLUSIVE,&subscription)==-EINVAL);
    assert(descriptors()==acquired && !subscription.ops);
    assert(!md_control_subscription(provider,sockets[0],-1,EPOLLIN|EPOLLEXCLUSIVE,&subscription));
    close(sockets[0]);
    char byte;
    assert(recv(sockets[1],&byte,1,MSG_DONTWAIT)==0);
    md_control_file_release(subscription); close(sockets[1]);
    release(s); md_control_event_release(source);
    assert(descriptors()==before);
    puts("PASS layered subscription rollback and native socket final-close semantics");
}
static void batch_retirement(void) {
    struct md_control_event *source=md_control_event_new(); assert(source);
    struct md_control_epoll *q=md_control_epoll_new(); assert(q);
    struct sample *items[97];
    for (unsigned i=0;i<97;++i) {
        items[i]=sample(source,0);
        assert(!ctl(q,EPOLL_CTL_ADD,(int)i,items[i],0,i));
    }
    assert(!md_control_event_publish(source));
    /* Remove pending kernel events before the pointer can be freed/reused. */
    for (unsigned i=0;i<97;i+=2) { release(items[i]); items[i]=NULL; }
    unsigned seen[97]={0},total=0;
    for (unsigned round=0;round<97 && total<48;++round) {
        struct epoll_event events[7];
        int n=md_control_epoll_collect(q,events,7); assert(n>=0);
        for (int i=0;i<n;++i) {
            unsigned id=(unsigned)events[i].data.u64;
            assert(id<97 && (id&1) && !seen[id]++ && events[i].events==EPOLLERR); ++total;
        }
    }
    assert(total==48); empty(q);
    md_control_epoll_release(q);
    for (unsigned i=1;i<97;i+=2) release(items[i]);
    md_control_event_release(source);
    puts("PASS virtual epoll multi-batch readiness and queued subscription retirement");
}
static void admission_failure(void) {
    unsigned before=descriptors();
    control_fail_allocation(1);
    assert(!md_control_epoll_new() && errno==ENOMEM);
    control_fail_allocation(0);
    struct md_control_event *source=md_control_event_new(); assert(source);
    struct sample *s=sample(source,EPOLLIN);
    struct md_control_epoll *q=md_control_epoll_new(); assert(q);
    control_fail_allocation(1);
    assert(ctl(q,EPOLL_CTL_ADD,9,s,EPOLLIN,9)==-ENOMEM);
    control_fail_allocation(0); empty(q);
    assert(md_control_epoll_descriptor(q)==-1);
    control_fail_allocation(2);
    assert(ctl(q,EPOLL_CTL_ADD,9,s,EPOLLIN,9)==-ENOMEM);
    control_fail_allocation(0);
    assert(!atomic_load(&s->identity.weak));
    assert(md_control_epoll_descriptor(q)==-1);
    unsigned references=atomic_load(&s->identity.references);
    s->error=-EIO;
    assert(ctl(q,EPOLL_CTL_ADD,9,s,EPOLLIN,9)==-EIO);
    s->error=0; assert(atomic_load(&s->identity.references)==references); empty(q);
    assert(md_control_epoll_descriptor(q)==-1);
    struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
    /* Exhaust only this fixture's descriptor table, retaining the hard limit. */
    struct rlimit small=limit;
    if (small.rlim_cur>256) small.rlim_cur=256;
    assert(!setrlimit(RLIMIT_NOFILE,&small));
    int held[256],count=0;
    while (count<256) {
        int fd=open("/dev/null",O_RDONLY|O_CLOEXEC);
        if (fd<0) { assert(errno==EMFILE); break; }
        held[count++]=fd;
    }
    struct md_control_epoll *metadata=md_control_epoll_new();
    assert(metadata && md_control_epoll_descriptor(metadata)==-1);
    md_control_epoll_release(metadata);
    /* Fail at each cold transport acquisition: source and lifetime duplicates,
     * internal epoll, then ready eventfd. No failed prefix publishes a wake. */
    for (unsigned available=0;available<4;++available) {
        assert(ctl(q,EPOLL_CTL_ADD,9,s,EPOLLIN,9)==-EMFILE);
        assert(atomic_load(&s->identity.references)==references);
        assert(md_control_epoll_descriptor(q)==-1 && count>0);
        assert(!close(held[--count]));
    }
    assert(!ctl(q,EPOLL_CTL_ADD,9,s,EPOLLIN,9));
    while (count) close(held[--count]);
    assert(!setrlimit(RLIMIT_NOFILE,&limit));
    struct epoll_event out;
    assert(md_control_epoll_collect(q,&out,1)==1 && out.data.u64==9);
    md_control_epoll_release(q); release(s); md_control_event_release(source);
    assert(descriptors()==before);
    puts("PASS virtual epoll admission: ENOMEM, provider failure and EMFILE rollback/recovery");
}
static void retired_oneshot(void) {
    unsigned before=descriptors();
    struct md_control_event *source=md_control_event_new(); assert(source);
    struct sample *s=sample(source,EPOLLIN);
    struct md_control_epoll *q=md_control_epoll_new(); assert(q);
    assert(!ctl(q,EPOLL_CTL_ADD,9,s,EPOLLIN|EPOLLONESHOT,12));
    struct epoll_event out;
    assert(md_control_epoll_collect(q,&out,1)==1 && out.data.u64==12);
    empty(q);
    struct md_fd_weak *weak=md_fd_weak_observe(&s->identity); assert(weak);
    release(s);
    assert(md_fd_weak_expired(weak));
    assert(!md_fd_weak_lock(weak));
    struct pollfd p={md_control_epoll_descriptor(q),POLLIN,0};
    assert(poll(&p,1,0)==1);
    /* Retirement wakes cleanup even when the source is disabled. It does not
     * fabricate an application HUP or rearm the one-shot subscription. */
    empty(q);
    md_fd_weak_release(weak); md_control_epoll_release(q); md_control_event_release(source);
    assert(descriptors()==before);
    puts("PASS final release wakes one-shot retirement without a fabricated event");
}
struct task_sample { struct md_fd_object object; uint64_t revision; uint32_t mask; };
static void task_destroy(struct md_fd_object *object) { free(object); }
static long task_poll(struct md_fd_object *object,const struct md_control_actor *actor) {
    return actor->status_flags ? ((struct task_sample *)object)->mask : 0;
}
static struct md_task_readiness_epoch task_epoch(struct md_fd_object *object,const struct md_control_actor *actor) {
    return (struct md_task_readiness_epoch){.domain=actor->pid,.pending=((struct task_sample *)object)->revision};
}
static const struct md_fd_object_type task_type={.destroy=task_destroy,.task_poll=task_poll,.task_poll_epoch=task_epoch};
static int task_collect(struct md_control_epoll *q,const struct md_control_actor *actor,
        const struct epoll_event *native,int count,struct epoll_event *out,int capacity) {
    assert(md_control_epoll_task_delivery_begin(q)==1);
    int result=md_control_epoll_task_collect(q,actor,native,count,out,capacity);
    md_control_epoll_task_delivery_finish(q,1);
    return result;
}
static void task_affinity(uint32_t policy) {
    unsigned before=descriptors();
    struct md_control_epoll *q=md_control_epoll_new(); assert(q);
    struct task_sample *s=calloc(1,sizeof(*s)); assert(s);
    md_fd_object_init(&s->object,&task_type); s->mask=EPOLLIN; s->revision=1;
    struct md_control_actor actor={.pid=17,.status_flags=1},other={.pid=18,.status_flags=1};
    struct md_epoll_task_control *operation=NULL,*busy=NULL;
    struct epoll_event e={.events=EPOLLIN|EPOLLET|policy,.data.u64=UINT64_MAX},native,out[4];
    assert(md_control_epoll_admit_begin(q)==1 && !md_control_epoll_admit_finish(q,1));
    assert(!md_control_epoll_task_begin(q,EPOLL_CTL_ADD,7,&s->object,&actor,&e,&native,&operation));
    assert(native.events==e.events);
    uint64_t token=native.data.u64;
    assert(token && token!=UINT64_MAX);
    assert(md_control_epoll_task_begin(q,EPOLL_CTL_ADD,8,&s->object,&actor,&e,&native,&busy)==-EAGAIN && !busy);
    assert(!md_control_epoll_task_ready(q,&actor));
    md_control_epoll_task_finish(operation,-EBADF);
    assert(!md_control_epoll_task_ready(q,&actor));
    assert(!md_control_epoll_task_begin(q,EPOLL_CTL_ADD,7,&s->object,&actor,&e,&native,&operation));
    assert(native.data.u64!=token); token=native.data.u64;
    md_control_epoll_task_finish(operation,0);
    assert(md_control_epoll_task_ready(q,&actor) && !md_control_epoll_task_ready(q,&other));
    assert(!task_collect(q,&other,NULL,0,out,4));
    struct epoll_event returned={.events=EPOLLIN,.data.u64=token};
    assert(md_control_epoll_task_delivery_begin(q)==1);
    assert(!md_control_epoll_task_delivery_begin(q));
    assert(md_control_epoll_task_collect(q,&actor,NULL,0,out,4)==1);
    assert(md_control_epoll_task_begin(q,EPOLL_CTL_ADD,8,&s->object,&actor,&e,&native,&busy)==-EAGAIN);
    md_control_epoll_task_delivery_finish(q,0);
    assert(task_collect(q,&actor,&returned,1,out,4)==1 && out[0].data.u64==UINT64_MAX);
    assert(!md_control_epoll_task_ready(q,&actor));
    assert(!task_collect(q,&actor,NULL,0,out,4));
    ++s->revision;
    assert(task_collect(q,&actor,NULL,0,out,4)==1);
    if (policy&EPOLLEXCLUSIVE) {
        assert(md_control_epoll_task_begin(q,EPOLL_CTL_MOD,7,&s->object,&actor,&e,&native,&busy)==-EINVAL);
        e.events=EPOLLIN;
        assert(md_control_epoll_task_begin(q,EPOLL_CTL_MOD,7,&s->object,&actor,&e,&native,&busy)==-EINVAL);
        assert(!md_control_epoll_task_begin(q,EPOLL_CTL_DEL,7,&s->object,&actor,
            (const struct epoll_event *)1,&native,&operation));
        md_control_epoll_task_finish(operation,0);
        e.events=EPOLLIN|EPOLLEXCLUSIVE|EPOLLONESHOT;
        assert(md_control_epoll_task_begin(q,EPOLL_CTL_ADD,7,&s->object,&actor,&e,&native,&busy)==-EINVAL);
        e.events=EPOLLIN|EPOLLONESHOT;
        assert(!md_control_epoll_task_begin(q,EPOLL_CTL_ADD,7,&s->object,&actor,&e,&native,&operation));
        md_control_epoll_task_finish(operation,0);
        returned.data.u64=native.data.u64;
    }
    e.events=EPOLLIN|EPOLLONESHOT;
    assert(!md_control_epoll_task_begin(q,EPOLL_CTL_MOD,7,&s->object,&actor,&e,&native,&operation));
    md_control_epoll_task_finish(operation,0);
    assert(md_control_epoll_task_delivery_begin(q)==1);
    assert(md_control_epoll_task_collect(q,&actor,NULL,0,out,4)==1);
    md_control_epoll_task_delivery_finish(q,0);
    assert(md_control_epoll_task_ready(q,&actor));
    assert(task_collect(q,&actor,NULL,0,out,4)==1);
    ++s->revision;
    assert(!task_collect(q,&actor,&returned,1,out,4));
    assert(!md_control_epoll_task_begin(q,EPOLL_CTL_DEL,7,&s->object,&actor,NULL,&native,&operation));
    md_control_epoll_task_finish(operation,0);
    assert(!task_collect(q,&actor,&returned,1,out,4));
    e.events=EPOLLIN;
    assert(!md_control_epoll_task_begin(q,EPOLL_CTL_ADD,7,&s->object,&actor,&e,&native,&operation));
    assert(native.data.u64!=token);
    md_control_epoll_task_finish(operation,0);
    s->mask=0;
    assert(!task_collect(q,&actor,&returned,1,out,4));
    md_fd_object_release(&s->object);
    assert(!task_collect(q,&actor,NULL,0,out,4));
    md_control_epoll_release(q); assert(descriptors()==before);
    puts("PASS task epoll: caller domain, edge/one-shot state, internal token isolation, reservation rollback and stale events");
}
void test_control_epoll(void) {
    struct md_control_epoll *q=md_control_epoll_new(); assert(q);
    struct md_fd_object *object=md_control_epoll_object(q);
    assert(md_control_epoll_native_wait(NULL));
    assert(md_control_epoll_native_wait(object));
    assert(md_control_epoll_admit_begin(q)==1);
    assert(!md_control_epoll_native_wait(object));
    assert(!md_control_epoll_admit_finish(q,0));
    assert(md_control_epoll_native_wait(object));
    assert(md_control_epoll_admit_begin(q)==1);
    assert(!md_control_epoll_admit_finish(q,1));
    assert(!md_control_epoll_native_wait(object));
    md_control_epoll_release(q);
    identities(); policies(); mixed(); exclusive(); subscription_lifetime();
    batch_retirement(); admission_failure(); retired_oneshot(); task_affinity(0); task_affinity(EPOLLEXCLUSIVE);
}
