#define _GNU_SOURCE
#include "task_wait.h"
#include "ipc_peer_service.h"
#include "ipc_origins.h"
#include "ipc_received.h"
#include "ipc_capsule.h"
#include "launch_peer.h"
#include "fs_wire.h"
#include "fs_worker.h"
#include "fs_engine.h"
#include "fs_buffer.h"
#include "fs_buffer_service.h"
#include "task_resources.h"
#include "event_wait.h"
#include "received_fds.h"
#include "fd_return.h"
#include "fd_channel.h"
#include "control_epoll.h"
#include "descriptor_inode.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/file.h>
#include <sys/prctl.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/wait.h>
#include <sys/socket.h>
#include <unistd.h>

/* Static ARM64 Bionic executables require 64-byte ELF TLS alignment. */
_Alignas(64) static _Thread_local int fail_allocation;
static _Thread_local int lock_notification=-1;
static _Thread_local unsigned fail_send_after;
long __real_md_fs_send_fds(int, const void *, size_t, const int *, unsigned);
long __wrap_md_fs_send_fds(int fd, const void *data, size_t size, const int *fds, unsigned count) {
    if (fail_send_after && !--fail_send_after) return -ETOOMANYREFS;
    return __real_md_fs_send_fds(fd,data,size,fds,count);
}
int __real_flock(int, int);
int __wrap_flock(int fd, int operation) {
    if (lock_notification>=0 && operation==LOCK_EX) {
        uint64_t one=1;
        assert(write(lock_notification,&one,sizeof(one))==sizeof(one));
        lock_notification=-1;
    }
    return __real_flock(fd,operation);
}
void *__real_calloc(size_t, size_t);
void *__wrap_calloc(size_t count, size_t size) {
    if (fail_allocation==1) {
        fail_allocation=0; errno=ENOMEM; return NULL;
    }
    return __real_calloc(count,size);
}
int __real_md_received_fds_annotate(struct md_received_fds *,struct md_fd_object *const *,unsigned);
int __wrap_md_received_fds_annotate(struct md_received_fds *p,struct md_fd_object *const *origins,unsigned count) {
    int inject=fail_allocation==2;
    if (inject) fail_allocation=1;
    int result=__real_md_received_fds_annotate(p,origins,count);
    /* Fail the annotation allocation independently of its spare capacity. */
    if (inject) assert(!fail_allocation && result==-ENOMEM);
    return result;
}
void md_test_prepare_libc_descriptors(void) {
    /* Bionic lazily retains a trace_marker FD on mutex contention when tracing
     * is enabled. Initialize it before measuring the runtime's FD ownership. */
    pthread_mutex_t mutex=PTHREAD_MUTEX_INITIALIZER;
    assert(!pthread_mutex_lock(&mutex));
    /* EVENT_WAIT: an already expired deadline exercises contention without a delay. */
    const struct timespec expired={0};
    assert(pthread_mutex_timedlock(&mutex,&expired)==ETIMEDOUT);
    assert(!pthread_mutex_unlock(&mutex) && !pthread_mutex_destroy(&mutex));
}
static unsigned descriptors(void) {
    DIR *dir=opendir("/proc/self/fd"); assert(dir);
    unsigned n=0; struct dirent *entry;
    while ((entry=readdir(dir))) if (entry->d_name[0]!='.') ++n;
    assert(!closedir(dir)); return n;
}
static void descriptor_balance(unsigned before,const char *scenario,unsigned mode,unsigned protected) {
    unsigned after=descriptors();
    if (after!=before) {
        fprintf(stderr,"FD balance %s mode=%u protected=%u before=%u after=%u\n",scenario,mode,protected,before,after);
        DIR *dir=opendir("/proc/self/fd"); assert(dir);
        struct dirent *entry;
        while ((entry=readdir(dir))) if (entry->d_name[0]!='.') {
            char target[PATH_MAX];
            ssize_t size=readlinkat(dirfd(dir),entry->d_name,target,sizeof(target)-1);
            if (size>=0) { target[size]=0; fprintf(stderr,"FD %s %s%s\n",entry->d_name,target,
                atoi(entry->d_name)==dirfd(dir) ? " (diagnostic directory)" : ""); }
        }
        closedir(dir);
    }
    assert(after==before);
}
static void pulse(int fd) { uint64_t one=1; assert(write(fd,&one,sizeof(one))==sizeof(one)); }
static void await(int fd) {
    /* EVENT_WAIT: exact fixture barrier/readiness, not a settling delay. */
    assert(md_event_wait_fd(fd,POLLIN,md_event_now()+5000000000LL)>=0);
}
struct owner { struct md_fs_worker *worker; struct md_ipc_peer_service *service; };
static void owner_close(struct owner *o) {
    assert(!md_fs_worker_stop(o->worker)); o->worker=NULL;
    md_ipc_peer_service_destroy(o->service); o->service=NULL;
}
enum operation { MARK, PAIR, STALL, ALLOCATION, ANNOTATION, LOCK_NOTICE, ORIGINS, DECLARE, PAIR_NATIVE, SEND_FAILURE, MESSAGE, READ_MESSAGE, DATAGRAM_NAME, DATAGRAM_BIND, NATIVE_DATAGRAM };
struct work {
    struct md_fs_work work;
    enum operation operation;
    int done, fds[2], sender_identity;
    const char *name;
    struct md_received_fds *received;
    struct md_fs_anchor *anchors[70];
};
static void run(struct md_fs_work *base, struct md_filesystem *fs, int error) {
    struct work *w=(struct work *)base;
    if (error) return;
    if (w->operation==DECLARE) {
        assert(!fs->ipc && !md_ipc_credentials_open(fs->ipc_store,MD_IPC_TRANSPORT_ORIGINS_V1,&fs->ipc));
    } else if (w->operation==PAIR || w->operation==PAIR_NATIVE || w->operation==DATAGRAM_BIND) {
        struct md_ipc_credentials *ipc=NULL;
        if (w->operation==PAIR_NATIVE)
            assert(!md_ipc_credentials_open(fs->ipc_store,MD_IPC_TRANSPORT_NATIVE,&ipc));
        else assert(!md_fs_ipc_authority(fs,&ipc));
        struct md_identity identity=md_identity_new(0,0);
        struct md_fs_request q={.operation=MD_FS_IPC,.flags=w->operation==DATAGRAM_BIND ? MD_IPC_BIND : MD_IPC_PAIR,
            .directory={w->fds[0],w->fds[1]},.peer=getpid()};
        struct md_fs_result result;
        md_ipc_credentials_execute(ipc,&identity,NULL,&q,&result,NULL);
        md_identity_release(&identity); assert(!result.error);
        if (w->operation==PAIR_NATIVE) md_ipc_credentials_close(ipc);
    } else if (w->operation==NATIVE_DATAGRAM) {
        struct md_fs_context context;
        assert(!md_fs_context_begin(fs,0,getpid(),&context));
        struct md_ipc_capsules *cache=NULL;
        struct md_received_fds *data=NULL;
        struct md_received_message plan;
        unsigned count=w->received->count-1;
        int indices[253];
        for (unsigned i=0;i<count;++i) indices[i]=(int)i+1;
        assert(!md_ipc_received_message(&cache,&context,w->received,indices,count,&plan,&data));
        assert(data && data->count==count && plan.count==count);
        for (unsigned i=0;i<count;++i) {
            struct stat original,delivered;
            assert(!plan.entries[i].flags && plan.entries[i].index==(int)i
                && plan.first[i]==i && plan.first[i+1]==i+1);
            assert(!fstat(w->received->fds[i+1],&original) && !fstat(data->fds[i],&delivered)
                && original.st_dev==delivered.st_dev && original.st_ino==delivered.st_ino);
        }
        md_received_fds_release(data); md_ipc_capsules_free(cache); md_fs_context_end(&context);
    } else if (w->operation==DATAGRAM_NAME) {
        struct md_fs_request q={.operation=MD_FS_SOCKET_BIND,.directory={AT_FDCWD,w->fds[0]},
            .path={w->name,NULL},.mode=0600};
        struct md_fs_result result;
        md_fs_execute(fs,&q,&result,NULL); assert(!result.error);
    } else if (w->operation==MESSAGE || w->operation==READ_MESSAGE) {
        struct md_ipc_credentials *ipc=NULL; assert(!md_fs_ipc_authority(fs,&ipc));
        struct md_identity identity=md_identity_new(0,0);
        struct md_fs_request q={.operation=MD_FS_IPC,.flags=w->operation==MESSAGE ? MD_IPC_MESSAGE_CREATE : MD_IPC_MESSAGE_READ,
            .directory={w->fds[0],w->operation==MESSAGE ? -1 : w->fds[1]},.peer=getpid(),.offset=getpid(),.attributes={.uid=1234,.gid=2345}};
        struct md_fs_result result;
        md_ipc_credentials_execute(ipc,&identity,NULL,&q,&result,NULL);
        md_identity_release(&identity); assert(!result.error);
        if (w->operation==MESSAGE) { assert(result.fd>=0); w->fds[1]=result.fd; }
        else {
            assert(result.fd>=0 && result.position>0 && result.info.uid==(w->sender_identity ? 0U : 1234U)
                && result.info.gid==(w->sender_identity ? 0U : 2345U));
            close(result.fd);
        }
    } else if (w->operation==STALL) {
        pulse(w->fds[0]); await(w->fds[1]); uint64_t value;
        assert(read(w->fds[1],&value,sizeof(value))==sizeof(value));
    } else if (w->operation==ORIGINS) {
        for (unsigned i=0;i<70;++i) {
            struct md_fs_request q={.operation=MD_FS_OPEN,.path={i ? "/" : "/origin-payload",NULL},
                .flags=i ? O_PATH : O_RDWR|O_CREAT|O_TRUNC|O_CLOEXEC,.mode=0600,.directory={-1,-1}};
            struct md_fs_result result;
            md_fs_execute(fs,&q,&result,&(struct md_fs_output){.origin=&w->anchors[i]});
            assert(!result.error && result.fd>=0 && w->anchors[i]);
            if (i) close(result.fd);
            else {
                w->fds[0]=result.fd;
                assert(write(result.fd,"payload",7)==7 && lseek(result.fd,2,SEEK_SET)==2);
            }
        }
    } else if (w->operation==ALLOCATION) fail_allocation=1;
    else if (w->operation==ANNOTATION) fail_allocation=2;
    else if (w->operation==SEND_FAILURE) fail_send_after=1;
    else if (w->operation==LOCK_NOTICE) lock_notification=w->fds[0];
}
static int completed(struct md_fs_work *base) {
    struct work *w=(struct work *)base; assert(!w->done++); return 0;
}
static void submit(struct owner *o, struct work *w) {
    w->work=(struct md_fs_work){.run=run,.complete=completed};
    assert(!md_fs_worker_submit(o->worker,&w->work));
}
static void finish(struct owner *o, struct work *w) {
    /* EVENT_WAIT: worker publication through its production completion queue. */
    int64_t deadline=md_event_now()+5000000000LL;
    while (!w->done) {
        assert(md_event_wait_fd(md_fs_worker_fd(o->worker),POLLIN,deadline)>=0);
        assert(!md_fs_worker_dispatch(o->worker));
    }
}
static struct owner owner_new(const char *store) {
    static unsigned serial;
    char endpoint[80]; snprintf(endpoint,sizeof(endpoint),"md-peer-worker-%d-%u",getpid(),++serial);
    struct owner o={0};
    assert(!md_fs_worker_start(store,endpoint,NULL,NULL,NULL,NULL,&o.worker));
    /* Service-loop wakeup ownership is established after startup publication. */
    struct work mark={.operation=DECLARE}; submit(&o,&mark); finish(&o,&mark);
    unsigned before=descriptors();
    assert(!md_ipc_peer_service_new(o.worker,&o.service) && descriptors()==before);
    return o;
}
static void pair(struct owner *o, int fd[2]) {
    assert(!socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,fd));
    struct work w={.operation=PAIR,.fds={fd[0],fd[1]}}; submit(o,&w); finish(o,&w);
}
static void control(int fd[2]) { assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_NONBLOCK|SOCK_CLOEXEC,0,fd)); }
static void block(struct owner *o, struct work *w) {
    *w=(struct work){.operation=STALL,.fds={eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK),eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK)}};
    assert(w->fds[0]>=0 && w->fds[1]>=0); submit(o,w); await(w->fds[0]);
}
static void unblock(struct owner *o, struct work *w) {
    pulse(w->fds[1]); finish(o,w); close(w->fds[0]); close(w->fds[1]);
}
struct result {
    struct md_ipc_peer_admission *request;
    struct md_ipc_peer *peer;
    pthread_t owner;
    int calls, error, take;
};
static void ready(struct md_ipc_peer_admission *r, int error, void *context) {
    struct result *out=context;
    assert(pthread_equal(pthread_self(),out->owner) && r==out->request && !out->calls++);
    out->error=error;
    if (out->take) assert(md_ipc_peer_take(&out->request,&out->peer)==error && !out->request);
}
static void begin(struct owner *o, int app, int ctl, struct result *out, int take) {
    *out=(struct result){.owner=pthread_self(),.take=take};
    assert(!md_ipc_peer_begin(o->service,app,ctl,1,0,ready,out,&out->request));
    assert(out->request && !out->calls);
}
static void joined_handshake(const char *store) {
    unsigned before=descriptors();
    struct owner owners[]={owner_new(store),owner_new(store)};
    int app[2], ctl[2]; pair(&owners[0],app); control(ctl);
    int data=fcntl(app[1],F_DUPFD_CLOEXEC,0); assert(data>=0);
    assert(send(app[0],"payload",7,MSG_NOSIGNAL)==7);
    struct result results[2];
    for (unsigned i=0;i<2;++i) {
        begin(&owners[i],app[i],ctl[i],&results[i],1);
        struct work mark={0}; submit(&owners[i],&mark); finish(&owners[i],&mark);
        assert(results[i].calls==1 && !results[i].error && results[i].peer);
    }
    char bytes[16]; assert(recv(data,bytes,sizeof(bytes),0)==7 && !memcmp(bytes,"payload",7));
    assert(!recv(data,bytes,sizeof(bytes),MSG_DONTWAIT)); close(data);
    for (unsigned i=0;i<2;++i) {
        assert(recv(ctl[i],bytes,sizeof(bytes),MSG_DONTWAIT)<0 && errno==EAGAIN);
        owner_close(&owners[i]);
    }
    int maintenance=open(store,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(maintenance>=0);
    assert(flock(maintenance,LOCK_EX|LOCK_NB)<0 && errno==EWOULDBLOCK);
    for (unsigned turn=0;turn<4;++turn) for (unsigned i=0;i<2;++i) assert(md_ipc_peer_step(results[i].peer)>=0);
    for (unsigned i=0;i<2;++i) {
        const struct md_ipc_binding *binding=NULL; uint64_t features=0;
        assert(!md_ipc_peer_agreement(results[i].peer,&binding,&features) && features==1 && binding->connection.side==i);
        md_ipc_peer_close(results[i].peer);
    }
    assert(!flock(maintenance,LOCK_EX|LOCK_NB) && !close(maintenance));
    assert(descriptors()==before);
    puts("PASS peer worker admission: two independent workers, supervisor callbacks, no early IO, application EOF, agreement after worker/SQLite teardown");
}
static void cancellation(const char *store, unsigned when) {
    unsigned before=descriptors(); struct owner o=owner_new(store);
    int app[2], ctl[2]; pair(&o,app); control(ctl);
    struct result out; struct work barrier;
    if (!when) block(&o,&barrier);
    begin(&o,app[0],ctl[0],&out,0);
    assert(md_ipc_peer_take(&out.request,&out.peer)==-EAGAIN && !out.peer);
    if (when==1) block(&o,&barrier);
    if (when==2) { struct work mark={0}; submit(&o,&mark); finish(&o,&mark); }
    md_ipc_peer_cancel(&out.request); assert(!out.request);
    if (when<2) unblock(&o,&barrier);
    struct work mark={0}; submit(&o,&mark); finish(&o,&mark);
    assert(out.calls==(when==2));
    char byte; assert(!recv(ctl[1],&byte,1,MSG_DONTWAIT) && !recv(app[1],&byte,1,MSG_DONTWAIT));
    close(ctl[1]); close(app[1]); owner_close(&o);
    assert(descriptors()==before);
    printf("PASS peer cancellation phase=%u: no cancelled callback, exact socket EOF and descriptor balance\n",when);
}
static void failure(const char *store) {
    unsigned before=descriptors(); struct owner o=owner_new(store);
    struct md_ipc_peer_service *failed=NULL;
    fail_allocation=1; assert(md_ipc_peer_service_new(o.worker,&failed)==-ENOMEM && !failed && !fail_allocation);
    for (unsigned mode=0;mode<3;++mode) {
        int app[2], ctl[2];
        if (mode==1) assert(!socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,app));
        else pair(&o,app);
        control(ctl);
        struct result out={.owner=pthread_self()};
        if (!mode) {
            fail_allocation=1;
            assert(md_ipc_peer_begin(o.service,app[0],ctl[0],1,0,ready,&out,&out.request)==-ENOMEM);
            assert(!out.request && !fail_allocation && fcntl(app[0],F_GETFD)>=0 && fcntl(ctl[0],F_GETFD)>=0);
            close(app[0]); close(ctl[0]);
        } else {
            struct work fault={.operation=ALLOCATION}, barrier;
            if (mode==2) { block(&o,&barrier); submit(&o,&fault); }
            begin(&o,app[0],ctl[0],&out,1);
            struct work mark={0}; submit(&o,&mark);
            if (mode==2) unblock(&o,&barrier);
            finish(&o,&mark);
            assert(out.calls==1 && out.error==(mode==1 ? -ENODATA : -ENOMEM) && !out.peer && !out.request);
        }
        char byte; assert(!recv(ctl[1],&byte,1,MSG_DONTWAIT) && !recv(app[1],&byte,1,MSG_DONTWAIT));
        close(app[1]); close(ctl[1]);
    }
    owner_close(&o); assert(descriptors()==before);
    puts("PASS peer worker allocation rollback and unknown-application rejection: explicit errors, no early descriptor transfer or leaked sockets");
}
static void cancel_running(const char *store) {
    unsigned before=descriptors(); struct owner o=owner_new(store);
    int app[2], ctl[2]; pair(&o,app); control(ctl);
    char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/ipc/gate",store);
    int gate=open(path,O_RDWR|O_CLOEXEC),notice=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
    assert(gate>=0 && notice>=0 && !flock(gate,LOCK_EX|LOCK_NB));
    struct work arm={.operation=LOCK_NOTICE,.fds={notice,-1}}; submit(&o,&arm); finish(&o,&arm);
    struct result out; begin(&o,app[0],ctl[0],&out,0);
    /* EVENT_WAIT: the admission passed its cancellation check and entered the
     * metadata lock. The main owner must cancel without waiting for this lock. */
    await(notice); md_ipc_peer_cancel(&out.request); assert(!out.request);
    assert(!flock(gate,LOCK_UN));
    struct work mark={0}; submit(&o,&mark); finish(&o,&mark);
    assert(!out.calls);
    char byte; assert(!recv(ctl[1],&byte,1,MSG_DONTWAIT) && !recv(app[1],&byte,1,MSG_DONTWAIT));
    close(gate); close(notice); close(ctl[1]); close(app[1]); owner_close(&o);
    assert(descriptors()==before);
    puts("PASS cancellation during worker metadata admission: nonblocking caller, retained work and no cancelled callback or leaked FD");
}
static void burst(const char *store) {
    enum { COUNT=96 };
    unsigned before=descriptors(); struct owner o=owner_new(store);
    int app[2], controls[COUNT]; pair(&o,app);
    struct result results[COUNT]; struct work barrier; block(&o,&barrier);
    for (unsigned i=0;i<COUNT;++i) {
        int ctl[2]; control(ctl); controls[i]=ctl[1];
        int captured=fcntl(app[0],F_DUPFD_CLOEXEC,0); assert(captured>=0);
        begin(&o,captured,ctl[0],&results[i],i%3==1);
    }
    for (unsigned i=0;i<COUNT;i+=3) md_ipc_peer_cancel(&results[i].request);
    struct work mark={0}; submit(&o,&mark); unblock(&o,&barrier); finish(&o,&mark);
    for (unsigned step=0;step<COUNT;++step) {
        unsigned i=(step*37)%COUNT;
        assert(results[i].calls==(i%3!=0));
        if (i%3==2 && i%2) md_ipc_peer_cancel(&results[i].request);
        else if (i%3==2) assert(!md_ipc_peer_take(&results[i].request,&results[i].peer));
        assert(!results[i].request); md_ipc_peer_close(results[i].peer);
        char byte; assert(!recv(controls[i],&byte,1,MSG_DONTWAIT)); close(controls[i]);
    }
    close(app[0]); char byte; assert(!recv(app[1],&byte,1,MSG_DONTWAIT)); close(app[1]);
    owner_close(&o); assert(descriptors()==before);
    puts("PASS 96 peer admissions: bounded worker batches, mixed cancellation/take order, one callback per owner and exact FD balance");
}
static void shutdown_pending(const char *store, int admitted) {
    unsigned before=descriptors(); struct owner o=owner_new(store);
    int app[2], ctl[2]; pair(&o,app); control(ctl);
    struct result out; struct work barrier;
    if (!admitted) block(&o,&barrier);
    begin(&o,app[0],ctl[0],&out,0);
    if (admitted) block(&o,&barrier);
    /* No dispatch: shutdown must reclaim queued or published worker-owned
     * records after join, not rely on a callback reaching the dead owner. */
    pulse(barrier.fds[1]); owner_close(&o);
    close(barrier.fds[0]); close(barrier.fds[1]);
    assert(!out.calls);
    char byte; assert(!recv(ctl[1],&byte,1,MSG_DONTWAIT) && !recv(app[1],&byte,1,MSG_DONTWAIT));
    close(ctl[1]); close(app[1]); assert(descriptors()==before);
    printf("PASS peer owner shutdown admitted=%d: undelivered completion, join-before-release and exact FD balance\n",admitted);
}
static int worker_failed(void *context, struct md_filesystem *fs, short events) {
    (void)context; (void)fs; return events ? -EIO : 0;
}
static void unavailable(const char *store) {
    unsigned before=descriptors(); struct owner o=owner_new(store);
    int app[2], ctl[2]; pair(&o,app); control(ctl);
    int error=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK); assert(error>=0);
    md_fs_worker_notifications(o.worker,error,NULL,worker_failed,NULL); pulse(error);
    /* EVENT_WAIT: injected worker failure publishes its terminal state. */
    int64_t deadline=md_event_now()+5000000000LL;
    while (!md_fs_worker_error(o.worker)) {
        assert(md_event_wait_fd(md_fs_worker_fd(o.worker),POLLIN,deadline)>=0);
        int result=md_fs_worker_dispatch(o.worker); assert(!result || result==-EIO);
    }
    struct result out={.owner=pthread_self()};
    assert(md_ipc_peer_begin(o.service,app[0],ctl[0],1,0,ready,&out,&out.request)==-ESHUTDOWN && !out.request);
    assert(fcntl(app[0],F_GETFD)>=0 && fcntl(ctl[0],F_GETFD)>=0);
    assert(md_fs_worker_stop(o.worker)==-EIO); md_ipc_peer_service_destroy(o.service);
    close(app[0]); close(app[1]); close(ctl[0]); close(ctl[1]); close(error);
    assert(descriptors()==before);
    puts("PASS failed worker rejects new admission without stealing caller descriptors");
}
void md_test_ipc_peer_worker(const char *path) {
    /* EVENT_WAIT: outer failure bound includes worker stall, stop and join. */
    alarm(45); setbuf(stdout,NULL);
    struct md_inode_store *store=NULL; assert(!md_inode_store_open(path,1,&store)); md_inode_store_close(store);
    joined_handshake(path);
    for (unsigned phase=0;phase<3;++phase) cancellation(path,phase);
    cancel_running(path); burst(path); failure(path); shutdown_pending(path,0); shutdown_pending(path,1); unavailable(path);
    puts("PASS asynchronous IPC peer admission");
}

struct transfer_result {
    struct md_ipc_origin_request *request;
    struct md_received_fds *received;
    pthread_t owner;
    int calls, error;
};
struct transfer_fixture {
    struct owner owner[2];
    struct md_ipc_origins *service[2];
    struct md_fd_message *message;
    struct md_received_fds *capture;
    struct transfer_result result[2];
    int application[2], payload_reader;
};
static const char launch_id[]="0123456789abcdef0123456789abcdef";
static void transfer_ready(struct md_ipc_origin_request *r, int error, void *context) {
    struct transfer_result *out=context;
    assert(pthread_equal(pthread_self(),out->owner) && r==out->request && !out->calls++);
    out->error=error;
}
static void mark(struct owner *o) { struct work w={0}; submit(o,&w); finish(o,&w); }
static struct md_fd_message *object_snapshot(struct md_fd_object *const *objects, unsigned count) {
    struct md_fd_origins *table=md_fd_origins_new(); assert(table && count<=253);
    int numbers[253]; struct md_fd_origin_update updates[253];
    for (unsigned i=0;i<count;++i) numbers[i]=(int)i+10;
    assert(!md_fd_origins_prepare_batch(table,objects,0,count,updates));
    assert(!md_fd_origins_finish_batch(updates,count,numbers,count));
    struct md_fd_order turn={0}; assert(md_fd_order_begin(table,&turn,NULL)==1 && !md_fd_order_start(&turn));
    struct md_fd_message *message=NULL;
    assert(!md_fd_message_capture(table,numbers,count,&message));
    assert(!md_fd_order_complete(&turn)); md_fd_origins_release(table);
    return message;
}
static struct md_fd_message *message_snapshot(struct md_fs_anchor *const *entries, unsigned count) {
    assert(count<=253);
    struct md_fd_object *objects[253];
    for (unsigned i=0;i<count;++i) objects[i]=md_fs_anchor_object(entries[i]);
    return object_snapshot(objects,count);
}
static struct md_received_fds *captured(const int *fds, unsigned count) {
    struct md_received_fds *p=NULL; assert(!md_received_fds_reserve(&p,count));
    for (unsigned i=0;i<count;++i) {
        int fd=fcntl(fds[i],F_DUPFD_CLOEXEC,0); assert(fd>=0); p->fds[p->count++]=fd;
    }
    return p;
}
static void transfer_new(struct transfer_fixture *f, const char *path) {
    *f=(struct transfer_fixture){.application={-1,-1},.payload_reader=-1};
    for (unsigned i=0;i<2;++i) {
        f->owner[i]=owner_new(path);
        assert(!md_ipc_origins_new(f->owner[i].worker,launch_id,&f->service[i]));
        f->result[i].owner=pthread_self();
    }
    pair(&f->owner[0],f->application);
    struct work w={.operation=ORIGINS}; submit(&f->owner[0],&w); finish(&f->owner[0],&w);
    struct md_fs_anchor *entries[253]; int data[254], pipefd[2];
    assert(!pipe2(pipefd,O_NONBLOCK|O_CLOEXEC)); f->payload_reader=pipefd[0];
    data[0]=f->application[0];
    for (unsigned i=0;i<253;++i) {
        entries[i]=i==252 ? NULL : w.anchors[i%70];
        data[i+1]=i==252 ? pipefd[1] : i%70 ? md_fs_anchor_fd(entries[i]) : w.fds[0];
    }
    f->message=message_snapshot(entries,253); f->capture=captured(data,254);
    for (unsigned i=0;i<70;++i) md_fs_anchor_release(w.anchors[i]);
    close(w.fds[0]); close(pipefd[1]);
}
static void transfer_wait(struct owner *o, struct transfer_result *r) {
    /* EVENT_WAIT: worker completion only; expiry fails, never advances state. */
    int64_t deadline=md_event_now()+5000000000LL;
    while (!r->calls) {
        assert(!md_event_wait_fd(md_fs_worker_fd(o->worker),POLLIN,deadline));
        assert(!md_fs_worker_dispatch(o->worker));
    }
}
static void prepare_begin(struct transfer_fixture *f) {
    assert(!md_ipc_origins_prepare(f->service[0],f->message,f->capture,
        transfer_ready,&f->result[0],&f->result[0].request));
    md_received_fds_release(f->capture); f->capture=NULL;
}
static void transfer_prepare(struct transfer_fixture *f) {
    prepare_begin(f); transfer_wait(&f->owner[0],&f->result[0]);
    enum md_ipc_ancillary_transport transport=MD_IPC_TRANSPORT_NATIVE;
    assert(!f->result[0].error && !md_ipc_origins_take(&f->result[0].request,&f->result[0].received,&transport)
        && transport==MD_IPC_TRANSPORT_ORIGINS_V1);
    assert(f->result[0].received->count==2 && !f->result[0].received->origins);
}
static void receive_begin(struct transfer_fixture *f) {
    int fd[]={f->application[1],f->result[0].received->fds[1]};
    struct md_received_fds *input=captured(fd,2);
    assert(!md_ipc_origins_receive(f->service[1],input,transfer_ready,&f->result[1],&f->result[1].request));
    md_received_fds_release(input);
}
static void transfer_exchange(struct transfer_fixture *f) {
    transfer_prepare(f);
    md_fd_message_export_end(f->message,1);
    receive_begin(f); transfer_wait(&f->owner[1],&f->result[1]);
    assert(!f->result[1].error && !md_ipc_origins_take(&f->result[1].request,&f->result[1].received,NULL));
}
static void transfer_close(struct transfer_fixture *f) {
    for (unsigned i=0;i<2;++i) {
        if (f->owner[i].worker) owner_close(&f->owner[i]);
        md_ipc_origins_destroy(f->service[i]); f->service[i]=NULL;
        md_received_fds_release(f->result[i].received); f->result[i].received=NULL;
        if (f->application[i]>=0) close(f->application[i]);
    }
    md_fd_message_release(f->message); md_received_fds_release(f->capture);
    if (f->payload_reader>=0) {
        char byte; await(f->payload_reader); assert(!read(f->payload_reader,&byte,1)); close(f->payload_reader);
    }
}
static void transfer_epoll(const char *path,unsigned typed) {
    unsigned before=descriptors();
    struct transfer_fixture f={.application={-1,-1},.payload_reader=-1};
    for (unsigned i=0;i<2;++i) {
        f.owner[i]=owner_new(path);
        assert(!md_ipc_origins_new(f.owner[i].worker,launch_id,&f.service[i]));
        f.result[i].owner=pthread_self();
    }
    pair(&f.owner[0],f.application);
    int ep=epoll_create1(EPOLL_CLOEXEC),wake=eventfd(1,EFD_NONBLOCK|EFD_CLOEXEC);
    assert(ep>=0 && wake>=0);
    struct epoll_event event={.events=EPOLLIN,.data.u64=UINT64_MAX},out;
    assert(!epoll_ctl(ep,EPOLL_CTL_ADD,wake,&event));
    struct md_control_epoll *q=md_control_epoll_new(); assert(q);
    struct md_fd_object *objects[2]={md_control_epoll_object(q),NULL};
    int fds[3]={f.application[0],ep,-1}; unsigned count=typed ? 2 : 1;
    struct md_identity root=md_identity_new(0,0);
    if (typed) {
        int ends[2]; assert(!pipe2(ends,O_CLOEXEC|O_NONBLOCK));
        f.payload_reader=ends[0]; fds[2]=ends[1];
        objects[1]=md_descriptor_inode_new(&root,S_IFIFO|0600); assert(objects[1]);
    }
    f.message=object_snapshot(objects,count); f.capture=captured(fds,count+1);
    transfer_prepare(&f);
    assert(md_fd_message_exports_native(f.message,0));
    assert(!md_fd_message_exports_native(f.message,1));
    assert(md_control_epoll_ctl(q,EPOLL_CTL_ADD,1,objects[0],(struct md_control_file){0},&event)==-EBUSY);
    /* Deliver to another worker/IPC owner with no local origin registry. The
     * capsule must preserve the native OFD, not require the sender's pointer. */
    md_fd_message_export_end(f.message,1);
    assert(md_control_epoll_ctl(q,EPOLL_CTL_ADD,1,objects[0],(struct md_control_file){0},&event)==-EOPNOTSUPP);
    close(ep); if (typed) close(fds[2]);
    receive_begin(&f); transfer_wait(&f.owner[1],&f.result[1]);
    assert(!f.result[1].error && !md_ipc_origins_take(&f.result[1].request,&f.result[1].received,NULL));
    struct md_received_fds *received=f.result[1].received;
    assert(received->count==count && (!received->origins || !received->origins[0]));
    assert(epoll_wait(received->fds[0],&out,1,0)==1 && out.data.u64==UINT64_MAX && out.events==EPOLLIN);
    if (typed) assert(received->origins && md_descriptor_inode_is(received->origins[1]));
    close(wake); transfer_close(&f);
    md_fd_object_release(objects[1]); md_control_epoll_release(q); md_identity_release(&root);
    assert(descriptors()==before);
    printf("PASS native epoll capsule typed=%u: independent receiver, original OFD and exact release\n",typed);
}
static void transfer_success(const char *path) {
    unsigned before=descriptors(); struct transfer_fixture f; transfer_new(&f,path);
    int replacement[2]; control(replacement);
    int number=f.application[0]; assert(dup2(replacement[0],number)==number);
    close(replacement[0]);
    transfer_prepare(&f);
    /* The original number now names another socket. Only retained custody is
     * permitted to choose the actual connection, metadata and send target. */
    struct md_received_fds *prepared=f.result[0].received;
    assert(md_fs_send(prepared->fds[0],"payload",7,&(struct md_fs_rights){.count=1,.fd={prepared->fds[1]}})==7);
    char bytes[16]; struct md_fs_rights rights={0};
    assert(md_fs_receive(f.application[1],bytes,sizeof(bytes),&rights)==7
        && !memcmp(bytes,"payload",7) && rights.count==1);
    assert(recv(replacement[1],bytes,sizeof(bytes),MSG_DONTWAIT)<0 && errno==EAGAIN);
    close(replacement[1]); close(rights.fd[0]);
    owner_close(&f.owner[0]); md_ipc_origins_destroy(f.service[0]); f.service[0]=NULL;
    receive_begin(&f); transfer_wait(&f.owner[1],&f.result[1]);
    assert(!f.result[1].error && !md_ipc_origins_take(&f.result[1].request,&f.result[1].received,NULL));
    struct md_received_fds *first=f.result[1].received;
    assert(first->count==253 && first->origins[0] && first->origins[0]==first->origins[70] && !first->origins[252]);
    assert(read(first->fds[0],bytes,1)==1 && bytes[0]=='y');
    f.result[1]=(struct transfer_result){.owner=pthread_self()};
    receive_begin(&f); transfer_wait(&f.owner[1],&f.result[1]);
    assert(!f.result[1].error && !md_ipc_origins_take(&f.result[1].request,&f.result[1].received,NULL));
    assert(lseek(f.result[1].received->fds[70],0,SEEK_CUR)==3);
    assert(read(f.result[1].received->fds[70],bytes,1)==1 && bytes[0]=='l');
    md_received_fds_release(first); transfer_close(&f); assert(descriptors()==before);
    puts("PASS capsule worker: actual captured socket after slot reuse, 253 annotated rights, source shutdown and repeatable shared OFD");
}
static void transfer_cancel(const char *path, unsigned kind, unsigned phase) {
    unsigned before=descriptors(); struct transfer_fixture f; transfer_new(&f,path);
    if (kind) transfer_prepare(&f);
    struct owner *o=&f.owner[kind]; struct transfer_result *r=&f.result[kind];
    struct work barrier; int gate=-1, notice=-1;
    if (!phase) block(o,&barrier);
    if (phase==1) {
        char file[PATH_MAX]; assert(snprintf(file,sizeof(file),"%s/ipc/gate",path)<(int)sizeof(file));
        gate=open(file,O_RDWR|O_CLOEXEC); assert(gate>=0 && !flock(gate,LOCK_EX));
        notice=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK); assert(notice>=0);
        struct work w={.operation=LOCK_NOTICE,.fds={notice,-1}}; submit(o,&w); finish(o,&w);
    }
    if (kind) receive_begin(&f); else prepare_begin(&f);
    if (phase==1) await(notice);
    if (phase==2) block(o,&barrier); /* completed operation, undispatched result */
    if (phase==3) transfer_wait(o,r);
    md_ipc_origins_cancel(&r->request); assert(!r->request);
    if (phase==1) { assert(!flock(gate,LOCK_UN)); close(gate); close(notice); }
    if (phase==0 || phase==2) unblock(o,&barrier);
    mark(o); assert(r->calls==(phase==3 ? 1 : 0));
    transfer_close(&f); assert(descriptors()==before);
    printf("PASS capsule cancellation kind=%u phase=%u: no late callback or lost custody\n",kind,phase);
}
static void transfer_join(const char *path, unsigned kind) {
    unsigned before=descriptors(); struct transfer_fixture f; transfer_new(&f,path);
    if (kind) transfer_prepare(&f);
    if (kind) receive_begin(&f); else prepare_begin(&f);
    struct work barrier; block(&f.owner[kind],&barrier);
    pulse(barrier.fds[1]);
    owner_close(&f.owner[kind]);
    assert(!f.result[kind].calls);
    md_ipc_origins_destroy(f.service[kind]); f.service[kind]=NULL; f.result[kind].request=NULL;
    close(barrier.fds[0]); close(barrier.fds[1]);
    transfer_close(&f); assert(descriptors()==before);
    printf("PASS capsule shutdown kind=%u: join retires undelivered result without callback\n",kind);
}
static void transfer_native(const char *path, unsigned mode, unsigned phase) {
    unsigned before=descriptors(); struct transfer_fixture f; transfer_new(&f,path);
    int app[2];
    if (mode==3) assert(!pipe2(app,O_NONBLOCK|O_CLOEXEC));
    else assert(!socketpair(AF_UNIX,(mode==2 ? SOCK_DGRAM : SOCK_STREAM)|SOCK_CLOEXEC|SOCK_NONBLOCK,0,app));
    if (mode==1) {
        struct work w={.operation=PAIR_NATIVE,.fds={app[0],app[1]}};
        submit(&f.owner[0],&w); finish(&f.owner[0],&w);
    }
    close(f.capture->fds[0]); f.capture->fds[0]=fcntl(app[0],F_DUPFD_CLOEXEC,0);
    assert(f.capture->fds[0]>=0); close(app[0]);
    struct md_received_fds *original=f.capture;
    int numbers[254]; memcpy(numbers,original->fds,sizeof(numbers));
    struct owner *o=&f.owner[0]; struct transfer_result *r=&f.result[0];
    struct work barrier;
    if (phase==1) block(o,&barrier);
    prepare_begin(&f);
    enum md_ipc_ancillary_transport transport=MD_IPC_TRANSPORT_ORIGINS_V1;
    assert(md_ipc_origins_take(&r->request,&r->received,&transport)==-EAGAIN
        && transport==MD_IPC_TRANSPORT_ORIGINS_V1 && !r->received);
    if (phase==2 || phase==4) block(o,&barrier);
    if (!phase || phase==3) transfer_wait(o,r);
    if (!phase) {
        int expected=mode==3 ? -ENOTSOCK : 0;
        assert(r->error==expected && md_ipc_origins_take(&r->request,&r->received,&transport)==expected);
        if (expected) assert(!r->received && transport==MD_IPC_TRANSPORT_ORIGINS_V1);
        else {
            assert(transport==MD_IPC_TRANSPORT_NATIVE && r->received==original
                && original->count==254 && !memcmp(numbers,original->fds,sizeof(numbers)));
            assert(send(original->fds[0],"n",1,MSG_NOSIGNAL)==1);
            char byte; assert(recv(app[1],&byte,1,MSG_DONTWAIT)==1 && byte=='n');
            assert(read(original->fds[1],&byte,1)==1 && byte=='y');
        }
    } else if (phase==4) {
        pulse(barrier.fds[1]); owner_close(o);
        md_ipc_origins_destroy(f.service[0]); f.service[0]=NULL; r->request=NULL;
        close(barrier.fds[0]); close(barrier.fds[1]); assert(!r->calls);
    } else {
        md_ipc_origins_cancel(&r->request); assert(!r->request);
        if (phase==1 || phase==2) unblock(o,&barrier);
        mark(o); assert(r->calls==(phase==3 ? 1 : 0));
    }
    close(app[1]); transfer_close(&f); assert(descriptors()==before);
    printf("PASS outgoing native selection mode=%u phase=%u: exact retained vector, no duplicate FD and terminal cleanup\n",mode,phase);
}
static void transfer_addressed(const char *path, unsigned mode) {
    unsigned before=descriptors(); struct owner o=owner_new(path);
    struct md_ipc_origins *service=NULL; assert(!md_ipc_origins_new(o.worker,launch_id,&service));
    int app[2];
    if (mode<4) {
        assert(!socketpair(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC|SOCK_NONBLOCK,0,app));
        struct work pair_work={.operation=PAIR,.fds={app[0],app[1]}};
        submit(&o,&pair_work); finish(&o,&pair_work);
    } else {
        for (unsigned i=0;i<2;++i) { app[i]=socket(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC|SOCK_NONBLOCK,0); assert(app[i]>=0); }
    }
    char name[80]; snprintf(name,sizeof(name),"/addressed-peer-%d-%u",getpid(),mode);
    struct sockaddr_un peer={.sun_family=AF_UNIX}; socklen_t length;
    if (mode==1 || mode==5) {
        struct work w={.operation=DATAGRAM_NAME,.fds={app[1],-1},.name=name};
        submit(&o,&w); finish(&o,&w);
        length=sizeof(peer); assert(!getsockname(app[1],(void *)&peer,&length));
    } else {
        memcpy(peer.sun_path+1,name,strlen(name));
        length=offsetof(struct sockaddr_un,sun_path)+strlen(name)+3;
        peer.sun_path[strlen(name)+2]='z'; /* binary abstract name */
        assert(!bind(app[1],(void *)&peer,length));
    }
    if (mode>=4) {
        struct work w={.operation=DATAGRAM_BIND,.fds={app[1],-1}};
        submit(&o,&w); finish(&o,&w);
        if (mode>=7) {
            struct sockaddr_un local=peer; local.sun_path[1]='s';
            assert(!bind(app[0],(void *)&local,length));
            w=(struct work){.operation=DATAGRAM_BIND,.fds={app[0],-1}};
            submit(&o,&w); finish(&o,&w);
        }
        if (mode==6 || mode==8) assert(!connect(app[0],(void *)&peer,length));
    }
    int other=socket(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC|SOCK_NONBLOCK,0); assert(other>=0);
    struct sockaddr_un alternate=peer; alternate.sun_path[1]='x';
    assert(!bind(other,(void *)&alternate,length));
    int directory=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(directory>=0);
    int input[]={app[0],directory}; struct md_received_fds *source=captured(input,2);
    struct md_fs_anchor *origins[]={NULL}; struct md_fd_message *message=message_snapshot(origins,1);
    struct md_fs_context task={.peer=getpid(),.task={.identity=md_identity_new(0,0)}};
    int addressed=mode!=3 && mode!=6 && mode!=8;
    struct md_ipc_send_options options={.addressed=addressed,.length=addressed ? length : 0,
        .credentials=1,.claimed={getpid(),1234,2345}};
    memcpy(&options.address,mode==2 ? &alternate : &peer,length);
    if (mode==1 || mode==5) {
        struct sockaddr_un *guest=(void *)&options.address;
        *guest=(struct sockaddr_un){.sun_family=AF_UNIX}; strcpy(guest->sun_path,name);
        options.length=offsetof(struct sockaddr_un,sun_path)+strlen(name)+1;
    }
    struct transfer_result out={.owner=pthread_self()};
    assert(!md_ipc_origins_send(service,message,source,&task,&options,transfer_ready,&out,&out.request));
    md_fd_message_release(message); md_received_fds_release(source); md_fs_context_end(&task);
    transfer_wait(&o,&out); assert(!out.error);
    assert(md_ipc_origins_take(&out.request,&out.received,NULL)==-EINVAL && out.request && !out.received);
    struct md_ipc_send_address destination={.anchor=-1}; enum md_ipc_ancillary_transport transport;
    assert(!md_ipc_origins_take_send(&out.request,&out.received,&transport,&destination));
    int framed=mode!=2;
    assert(transport==(framed ? MD_IPC_TRANSPORT_ORIGINS_V1 : MD_IPC_TRANSPORT_NATIVE)
        && destination.anchor==-1 && destination.credentials==(unsigned)framed
        && out.received->count==(framed ? 3U : 2U));
    if (addressed) assert(destination.length==length
        && !memcmp(&destination.address,mode==2 ? &alternate : &peer,length));
    char byte='d'; struct iovec io={&byte,1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(2*sizeof(int))]; } control={0};
    unsigned count=out.received->count-1;
    control.align=(struct cmsghdr){.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS,.cmsg_len=CMSG_LEN(count*sizeof(int))};
    memcpy(CMSG_DATA(&control.align),out.received->fds+1,count*sizeof(int));
    struct msghdr msg={.msg_iov=&io,.msg_iovlen=1,.msg_control=&control,.msg_controllen=CMSG_SPACE(count*sizeof(int)),
        .msg_name=addressed ? &destination.address : NULL,.msg_namelen=addressed ? destination.length : 0};
    assert(sendmsg(out.received->fds[0],&msg,MSG_NOSIGNAL)==1);
    struct md_fs_rights received={0};
    assert(md_fs_receive(mode==2 ? other : app[1],&byte,1,&received)==1 && byte=='d' && received.count==count);
    if (framed) {
        struct work w={.operation=READ_MESSAGE,.fds={app[1],received.fd[1]}};
        submit(&o,&w); finish(&o,&w);
        struct transfer_result restored={.owner=pthread_self()};
        int fds[]={app[1],received.fd[0]};
        struct md_received_fds *input=captured(fds,2);
        assert(!md_ipc_origins_receive(service,input,transfer_ready,&restored,&restored.request));
        md_received_fds_release(input); transfer_wait(&o,&restored);
        assert(!restored.error && !md_ipc_origins_take(&restored.request,&restored.received,NULL));
        struct stat a,b;
        assert(restored.received->count==1 && !fstat(directory,&a) && !fstat(restored.received->fds[0],&b)
            && a.st_dev==b.st_dev && a.st_ino==b.st_ino);
        md_received_fds_release(restored.received);
    } else {
        struct stat a,b; assert(!fstat(directory,&a) && !fstat(received.fd[0],&b)
            && a.st_dev==b.st_dev && a.st_ino==b.st_ino);
    }
    assert(recv(mode==2 ? app[1] : other,&byte,1,MSG_DONTWAIT)==-1 && errno==EAGAIN);
    md_fs_close_rights(&received); md_received_fds_release(out.received);
    close(directory); close(other); close(app[0]); close(app[1]);
    owner_close(&o); md_ipc_origins_destroy(service); descriptor_balance(before,"addressed",mode,0);
    printf("PASS addressed peer mode=%u: resolved destination selects capsule and credentials, exact cleanup\n",mode);
}
static void transfer_native_datagram(const char *path) {
    unsigned before=descriptors(); struct owner o=owner_new(path);
    int receiver=socket(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC|SOCK_NONBLOCK,0); assert(receiver>=0);
    struct sockaddr_un address={.sun_family=AF_UNIX};
    snprintf(address.sun_path+1,sizeof(address.sun_path)-1,"md-native-dgram-%d",getpid());
    assert(!bind(receiver,(void *)&address,sizeof(address)));
    struct work bound={.operation=DATAGRAM_BIND,.fds={receiver,-1}};
    submit(&o,&bound); finish(&o,&bound);
    int sockets[2],pipefd[2];
    assert(!socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC|SOCK_NONBLOCK,0,sockets));
    assert(!pipe2(pipefd,O_NONBLOCK|O_CLOEXEC));
    int directory=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(directory>=0);
    const char bytes[]="native queued data";
    assert(write(sockets[0],bytes,sizeof(bytes))==sizeof(bytes));
    int native[]={directory,pipefd[0],sockets[0],sockets[1]};
    int numbers[254]={receiver};
    for (unsigned pass=0;pass<2;++pass) {
        unsigned count=pass ? 253 : 4;
        for (unsigned i=0;i<count;++i) numbers[i+1]=native[i%4];
        struct md_received_fds *source=captured(numbers,count+1);
        struct work inspect={.operation=NATIVE_DATAGRAM,.received=source};
        submit(&o,&inspect); finish(&o,&inspect); md_received_fds_release(source);
    }
    char out[sizeof(bytes)];
    assert(read(sockets[1],out,sizeof(out))==sizeof(out) && !memcmp(out,bytes,sizeof(out)));
    close(directory); close(pipefd[0]); close(pipefd[1]); close(sockets[0]); close(sockets[1]); close(receiver);
    owner_close(&o); descriptor_balance(before,"native datagram",0,0);
    puts("PASS native datagram rights: ordinary files, pipes, empty and queued sockets, exact cleanup");
}
static void transfer_failures(const char *path, unsigned mode) {
    unsigned before=descriptors(); struct transfer_fixture f; transfer_new(&f,path);
    unsigned kind=mode==2 || mode>=4;
    if (kind) transfer_prepare(&f);
    struct work w={.operation=mode==1 ? SEND_FAILURE : mode==2 ? ANNOTATION : ALLOCATION};
    if (mode<3) { submit(&f.owner[kind],&w); finish(&f.owner[kind],&w); }
    int expected=mode==1 ? -ETOOMANYREFS : -ENOMEM;
    struct md_received_fds *input=NULL;
    if (kind) {
        int fd[]={f.application[1],f.result[0].received->fds[1]};
        if (mode==4) { fd[0]=f.application[0]; expected=-EXDEV; }
        if (mode==5 || mode==6) {
            int app[2]; control(app);
            if (mode==5) {
                struct work native={.operation=PAIR_NATIVE,.fds={app[0],app[1]}};
                submit(&f.owner[1],&native); finish(&f.owner[1],&native);
            }
            fd[0]=app[1]; input=captured(fd,2); close(app[0]); close(app[1]);
            expected=mode==5 ? -ENOTSUP : -ENODATA;
        }
        if (!input) input=captured(fd,2);
    }
    struct transfer_result *r=&f.result[kind];
    if (mode==3) fail_allocation=1;
    int error=kind ? md_ipc_origins_receive(f.service[1],input,transfer_ready,r,&r->request)
        : md_ipc_origins_prepare(f.service[0],f.message,f.capture,transfer_ready,r,&r->request);
    if (mode==3) assert(error==-ENOMEM && !r->request && !r->calls);
    else {
        assert(!error);
        assert(md_ipc_origins_take(&r->request,&r->received,NULL)==-EAGAIN && r->request);
        transfer_wait(&f.owner[kind],r);
        assert(r->error==expected && md_ipc_origins_take(&r->request,&r->received,NULL)==expected && !r->received);
    }
    md_received_fds_release(input);
    transfer_close(&f); assert(descriptors()==before);
    printf("PASS capsule failure mode=%u: explicit error, unchanged caller custody and exact cleanup\n",mode);
}
static void transfer_burst(const char *path) {
    unsigned before=descriptors(); struct transfer_fixture f; transfer_new(&f,path); transfer_prepare(&f);
    struct work barrier; block(&f.owner[1],&barrier);
    struct transfer_result out[17]={0};
    int fds[]={f.application[1],f.result[0].received->fds[1]};
    struct md_received_fds *input=captured(fds,2);
    for (unsigned i=0;i<17;++i) {
        out[i].owner=pthread_self();
        int r=md_ipc_origins_receive(f.service[1],input,transfer_ready,&out[i],&out[i].request);
        assert(i==16 ? r==-EBUSY && !out[i].request : !r);
    }
    md_received_fds_release(input);
    for (unsigned i=1;i<16;i+=2) md_ipc_origins_cancel(&out[i].request);
    unblock(&f.owner[1],&barrier); mark(&f.owner[1]);
    for (unsigned i=0;i<16;++i) {
        if (i%2) assert(!out[i].calls && !out[i].request);
        else {
            assert(out[i].calls==1 && !out[i].error && !md_ipc_origins_take(&out[i].request,&out[i].received,NULL));
            assert(out[i].received->count==253); md_received_fds_release(out[i].received);
        }
    }
    transfer_close(&f); assert(descriptors()==before);
    puts("PASS capsule burst: bounded admission, mixed cancellation, independent results and exact cleanup");
}
struct vector_receiver {
    struct md_fd_receipt *receipt;
    uint64_t token;
    int endpoint, flags, skip, slots;
    pid_t parent;
};
static int vector_receive(void *context) {
    struct vector_receiver *r=context;
    if (prctl(PR_SET_PDEATHSIG,SIGKILL) || getppid()!=r->parent
            || prctl(PR_SET_DUMPABLE,0)) _exit(91);
    if (r->slots>=0) {
        struct rlimit limit;
        if (getrlimit(RLIMIT_NOFILE,&limit)) _exit(93);
        unsigned available=0, last=0;
        while (available<(unsigned)r->slots && last<4096 && last<limit.rlim_cur) {
            if (fcntl((int)last,F_GETFD)<0) {
                if (errno!=EBADF) _exit(94);
                ++available;
            }
            ++last;
        }
        if (available!=(unsigned)r->slots) _exit(95);
        limit.rlim_cur=last;
        if (setrlimit(RLIMIT_NOFILE,&limit)) _exit(96);
    }
    if (!r->skip && recvmsg(r->endpoint,&r->receipt->message,MSG_DONTWAIT|r->flags)!=8) _exit(92);
    _exit(0);
}
static void transfer_publication(const char *path, unsigned mode) {
    unsigned before=descriptors(); struct transfer_fixture f; transfer_new(&f,path); transfer_exchange(&f);
    struct md_received_fds *custody=f.result[1].received; f.result[1].received=NULL;
    unsigned count=custody->count; assert(count==253);
    int payload_reader=f.payload_reader; f.payload_reader=-1;
    transfer_close(&f);
    int maintenance=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(maintenance>=0);
    assert(flock(maintenance,LOCK_EX|LOCK_NB)<0 && errno==EWOULDBLOCK);

    int indices[253], sources[253], numbers[253]; struct md_fd_object *origins[253];
    struct md_fd_origin_update updates[253];
    for (unsigned i=0;i<count;++i) indices[i]=(i+252)%253;
    /* Unknown first, repeated views later: publication must preserve the chosen
     * order, not the importer's original indices or the physical inode alone. */
    assert(!md_received_fds_select(custody,indices,count,sources,origins));
    assert(!origins[0] && origins[1] && origins[1]==origins[71] && origins[1]!=origins[2]);
    struct md_fd_channel *channel=NULL; assert(!md_fd_channel_new(&channel));
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    int endpoint=fcntl(md_fd_channel_endpoint(channel),F_DUPFD_CLOEXEC,0); assert(endpoint>=0);
    struct md_fd_origin_update update;
    assert(!md_fd_origins_prepare_channel(files,channel,FD_CLOEXEC,&update));
    md_fd_origins_finish(&update,endpoint);
    void *memory=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    void *stack=mmap(NULL,65536,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(memory!=MAP_FAILED && stack!=MAP_FAILED);
    struct vector_receiver *receiver=memory;
    struct md_fd_receipt *receipt=(void *)((char *)memory+128);
    assert(128+md_fd_receipt_size(count)<=4096 && !md_fd_receipt_reset(receipt,count));
    unsigned flags=mode==4 || mode==5 ? 0 : MSG_CMSG_CLOEXEC;
    *receiver=(struct vector_receiver){.receipt=receipt,.endpoint=endpoint,.parent=getpid(),
        .flags=(int)flags|(mode==2 ? MSG_PEEK : 0),.skip=mode==3,
        .slots=mode==7 ? 3 : mode==8 ? 0 : -1};
    receipt->vector=(struct iovec){&receiver->token,sizeof(receiver->token)};
    unsigned expected=mode==1 || mode==7 ? 3 : mode==3 || mode==4 || mode==8 ? 0 : count;
    if (mode==1 || mode==4) receipt->message.msg_controllen=expected ? CMSG_LEN(expected*sizeof(int)) : 0;
    struct md_fd_return_publication publication={.origins=origins,.updates=updates,.numbers=numbers,
        .memory=receipt,.flags=flags ? FD_CLOEXEC : 0};
    struct md_fd_return result={0};
    assert(!md_fd_return_vector_begin(&result,files,sources,count,&publication,NULL));
    assert(md_fd_return_progress(&result,NULL)==MD_FD_RETURN_READY);
    struct md_fd_order successor={0}; assert(!md_fd_order_begin(files,&successor,NULL));
    uint64_t token=result.request;
    md_received_fds_release(custody); custody=NULL;
    /* Kernel queue now owns data descriptions; prepared publication alone owns
     * origins. No worker, import vector or source custody remains. */
    assert(flock(maintenance,LOCK_EX|LOCK_NB)<0 && errno==EWOULDBLOCK);
    pid_t child=clone(vector_receive,(char *)stack+65536,CLONE_FILES|SIGCHLD,receiver); assert(child>0);
    /* EVENT_WAIT: exact receiver death, bounded by the fixture alarm. There is
     * no guest acknowledgement; publication precedes the next table mutation. */
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    int installed[253]; assert(md_fd_receipt_read(receipt,count,installed)==(int)expected);
    assert(md_fd_order_state(&successor)==MD_RESOURCE_ORDER_QUEUED);
    if (mode==0) assert(md_fd_return_finish(&result,-ECANCELED,0)==-ECANCELED);
    else if (mode==4 || mode==6) assert(!md_fd_return_finish(&result,8,receiver->token));
    else assert(!md_fd_return_cancel(&result));
    assert(md_fd_order_state(&successor)==MD_RESOURCE_ORDER_READY);
    assert(!md_fd_order_cancel(&successor));
    if (mode!=3) assert(receiver->token==token);
    for (unsigned i=0;i<expected;++i) {
        assert(numbers[i]==installed[i]);
        unsigned fdflags=~0U;
        struct md_fs_anchor *origin=md_fd_origins_get(files,installed[i],&fdflags);
        assert(md_fs_anchor_object(origin)==origins[i] && fdflags==(origin && flags ? FD_CLOEXEC : 0));
        if (origin) {
            struct stat actual, identity;
            assert(!fstat(installed[i],&actual) && !fstat(md_fs_anchor_fd(origin),&identity));
            assert(actual.st_dev==identity.st_dev && actual.st_ino==identity.st_ino);
            if (indices[i]%70) assert((fcntl(installed[i],F_GETFL)&O_PATH)==O_PATH);
            else assert((fcntl(installed[i],F_GETFL)&(O_PATH|O_ACCMODE))==O_RDWR
                && lseek(installed[i],0,SEEK_CUR)==2);
        } else {
            char byte;
            assert(write(installed[i],"p",1)==1 && read(payload_reader,&byte,1)==1 && byte=='p');
        }
        assert(fcntl(installed[i],F_GETFD)==(flags ? FD_CLOEXEC : 0));
        md_fs_anchor_release(origin); assert(!close(installed[i]));
    }
    assert(!close(endpoint)); md_fd_channel_release(channel);
    if (expected>1) assert(flock(maintenance,LOCK_EX|LOCK_NB)<0 && errno==EWOULDBLOCK);
    md_fd_origins_release(files);
    assert(!flock(maintenance,LOCK_EX|LOCK_NB) && !close(maintenance));
    char byte; assert(!read(payload_reader,&byte,1)); close(payload_reader);
    assert(!munmap(memory,4096) && !munmap(stack,65536) && descriptors()==before);
    printf("PASS imported origin publication mode=%u: protected terminal receiver, %u installed, table ordering, retained store lease and exact release\n",mode,expected);
}

static void reap_child(pid_t pid) {
    /* EVENT_WAIT: exact fixture process; outer alarm bounds failed completion. */
    int status; assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
}
static pid_t exec_child(const char *path, int channel, unsigned role) {
    pid_t parent=getpid(), child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL) && getppid()==parent);
        assert(!fcntl(channel,F_SETFD,0));
        char fd[24], mode[8]; snprintf(fd,sizeof(fd),"%d",channel); snprintf(mode,sizeof(mode),"%u",role);
        execl("/proc/self/exe","fs-dispatch","--ipc-origin-child",path,fd,mode,(char *)NULL); _exit(90);
    }
    return child;
}
void md_test_ipc_origin_child(const char *path, int channel, unsigned role) {
    alarm(30); assert(!fcntl(channel,F_SETFD,FD_CLOEXEC));
    if (!role) {
        unsigned before=descriptors(); struct transfer_fixture f; transfer_new(&f,path); transfer_prepare(&f);
        assert(md_fs_send(channel,"C",1,&(struct md_fs_rights){.count=2,
            .fd={f.application[1],f.result[0].received->fds[1]}})==1);
        assert(md_fs_send(channel,"R",1,&(struct md_fs_rights){.count=1,.fd={f.payload_reader}})==1);
        close(f.payload_reader); f.payload_reader=-1;
        transfer_close(&f); assert(descriptors()==before); close(channel); return;
    }
    await(channel); char byte; struct md_fs_rights rights={0};
    assert(md_fs_receive(channel,&byte,1,&rights)==1 && byte=='C' && rights.count==2);
    struct md_received_fds *input=captured(rights.fd,2); md_fs_close_rights(&rights); close(channel);
    struct owner o=owner_new(path); struct md_ipc_origins *service=NULL;
    assert(!md_ipc_origins_new(o.worker,launch_id,&service));
    struct transfer_result out={.owner=pthread_self()};
    assert(!md_ipc_origins_receive(service,input,transfer_ready,&out,&out.request));
    md_received_fds_release(input);
    transfer_wait(&o,&out); assert(!out.error);
    /* Deliberate process loss with an untaken result and a live worker. */
    _exit(0);
}
static void transfer_exec(const char *path, unsigned abandoned) {
    unsigned before=descriptors(); int setup[2]; control(setup);
    pid_t child=exec_child(path,setup[1],0); close(setup[1]); await(setup[0]); reap_child(child);
    char byte; struct md_fs_rights rights={0};
    assert(md_fs_receive(setup[0],&byte,1,&rights)==1 && byte=='C' && rights.count==2);
    struct md_received_fds *input=captured(rights.fd,2); md_fs_close_rights(&rights);
    assert(md_fs_receive(setup[0],&byte,1,&rights)==1 && byte=='R' && rights.count==1);
    int reader=rights.fd[0]; close(setup[0]);
    assert(read(reader,&byte,1)<0 && errno==EAGAIN);
    if (abandoned) {
        control(setup); child=exec_child(path,setup[1],1); close(setup[1]);
        assert(md_fs_send(setup[0],"C",1,&(struct md_fs_rights){.count=2,
            .fd={input->fds[0],input->fds[1]}})==1);
        md_received_fds_release(input); close(setup[0]); reap_child(child);
    } else {
        struct owner o=owner_new(path); struct md_ipc_origins *service=NULL;
        assert(!md_ipc_origins_new(o.worker,launch_id,&service));
        struct transfer_result out={.owner=pthread_self()};
        assert(!md_ipc_origins_receive(service,input,transfer_ready,&out,&out.request));
        md_received_fds_release(input); transfer_wait(&o,&out);
        assert(!out.error && !md_ipc_origins_take(&out.request,&out.received,NULL));
        owner_close(&o); md_ipc_origins_destroy(service);
        assert(out.received->count==253 && out.received->origins[0] && !out.received->origins[252]);
        assert(read(out.received->fds[0],&byte,1)==1 && byte=='y');
        md_received_fds_release(out.received);
    }
    await(reader); assert(!read(reader,&byte,1)); close(reader);
    int root=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(root>=0);
    assert(!flock(root,LOCK_EX|LOCK_NB)); close(root);
    assert(descriptors()==before);
    printf("PASS capsule exec abandoned=%u: dead source, fresh worker, kernel-only custody and final release\n",abandoned);
}
static struct inspection_result {
    struct md_fs_buffer_request *request;
    struct md_received_fds *received;
    unsigned calls;
    int error, take;
} inspected;
static void inspected_ready(struct md_fs_buffer_request *request, pid_t actor, int error) {
    assert(actor==getpid() && request==inspected.request && !inspected.calls++);
    inspected.error=error; inspected.request=NULL;
    if (inspected.take && !error) assert(md_fs_buffer_inspect_take(request,&inspected.received)==1);
}
static void transfer_inspection(const char *path, unsigned mode) {
    unsigned before=descriptors(); struct transfer_fixture f;
    transfer_new(&f,path); transfer_prepare(&f);
    struct md_task_registry *tasks=md_task_registry_create(); assert(tasks);
    struct md_task_resources resources={.fs=md_domain_new(),.files=md_fd_origins_new(),
        .mounts=md_fs_worker_mounts(f.owner[1].worker)};
    struct md_identity identity=md_identity_new(0,0);
    assert(resources.fs && resources.files && !md_task_registry_publish(tasks,getpid(),getpid(),&identity,&resources));
    int wake=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK); assert(wake>=0);
    struct md_resource_wakeup *token=NULL; assert(!md_resource_wakeup_create(wake,&token));
    struct md_task_wait wait={0};
    struct md_fs_buffer_service *service=md_fs_buffer_service_new(f.owner[1].worker,tasks,token,&wait,inspected_ready);
    assert(service);
    struct md_interception_buffers *pool=NULL;
    assert(!md_buffers_create(1,sizeof(struct md_fs_buffer_frame),sizeof(struct md_fs_buffer_frame),0,&pool));
    struct md_interception_buffer buffer={0}; assert(!md_buffers_take(pool,&buffer));
    struct md_buffer_span span; assert(!md_buffer_span(&buffer,&span));
    struct md_fs_buffer_frame *frame=span.data;
    frame->rights.count=1; frame->rights.index[0]=1;
    int fds[]={f.application[mode==5 ? 0 : 1],f.result[0].received->fds[1]};
    struct md_received_fds *input=captured(fds,2);
    inspected=(struct inspection_result){.take=mode!=4};
    struct work barrier;
    if (mode==1) block(&f.owner[1],&barrier);
    if (mode==6) { struct work w={.operation=ANNOTATION}; submit(&f.owner[1],&w); finish(&f.owner[1],&w); }
    assert(!md_fs_buffer_inspect_begin(service,getpid(),resources.fs,&buffer,input,&inspected.request));
    md_received_fds_release(input); md_task_registry_forget(tasks,getpid());
    frame->rights.count=0; frame->rights.index[0]=-1;
    assert(!md_buffer_release(&buffer));
    if (mode==2 || mode==3) block(&f.owner[1],&barrier);
    if (mode==1 || mode==2) {
        md_fs_buffer_cancel(&inspected.request); unblock(&f.owner[1],&barrier);
    }
    if (mode==3) {
        pulse(barrier.fds[1]); owner_close(&f.owner[1]);
        close(barrier.fds[0]); close(barrier.fds[1]);
    } else { mark(&f.owner[1]); owner_close(&f.owner[1]); }
    md_fs_buffer_service_destroy(service); inspected.request=NULL;
    assert(inspected.calls==(mode>=1 && mode<=3 ? 0U : 1U));
    assert(inspected.error==(mode==5 ? -EXDEV : mode==6 ? -ENOMEM : 0));
    if (!mode) {
        assert(inspected.received && inspected.received->count==253 && inspected.received->origins[1]);
        assert(frame->received_message.count==253 && frame->received_message.first[0]==0
            && frame->received_message.first[1]==253);
        for (unsigned i=0;i<253;++i) assert(frame->received_message.entries[i].index==(int)i);
        md_received_fds_release(inspected.received); inspected.received=NULL;
    } else assert(!inspected.received);
    assert(!md_buffers_take(pool,&buffer) && !md_buffer_release(&buffer)); md_buffers_release(pool);
    md_task_registry_destroy(tasks); md_fd_origins_release(resources.files); md_domain_release(resources.fs);
    md_identity_release(&identity); md_resource_wakeup_release(token); close(wake);
    transfer_close(&f); assert(descriptors()==before);
    int maintenance=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(maintenance>=0);
    assert(!flock(maintenance,LOCK_EX|LOCK_NB)); close(maintenance);
    printf("PASS capsule buffer inspection mode=%u: captured context, cancellation, join and result ownership\n",mode);
}
void md_test_ipc_origins(const char *path) {
    /* EVENT_WAIT: total bound covers all native callbacks, joins and reaping. */
    alarm(90); setbuf(stdout,NULL);
    struct md_inode_store *store=NULL; assert(!md_inode_store_open(path,1,&store)); md_inode_store_close(store);
    for (unsigned typed=0;typed<2;++typed) transfer_epoll(path,typed);
    transfer_success(path);
    for (unsigned mode=0;mode<3;++mode)
        for (unsigned phase=0;phase<5;++phase) transfer_native(path,mode,phase);
    transfer_native(path,3,0);
    for (unsigned mode=0;mode<9;++mode) transfer_addressed(path,mode);
    transfer_native_datagram(path);
    for (unsigned i=0;i<9;++i) transfer_publication(path,i);
    for (unsigned kind=0;kind<2;++kind) {
        for (unsigned phase=0;phase<4;++phase) transfer_cancel(path,kind,phase);
        transfer_join(path,kind);
    }
    for (unsigned i=0;i<7;++i) transfer_failures(path,i);
    transfer_burst(path);
    transfer_exec(path,0); transfer_exec(path,1);
    for (unsigned i=0;i<7;++i) transfer_inspection(path,i);
    puts("PASS asynchronous IPC origin transfer");
}

static void transfer_guest_send(const char *path, const char *runner, unsigned mode, unsigned protected) {
    unsigned before=descriptors();
    struct owner o=owner_new(path); int application[2]; pair(&o,application);
    pid_t parent=getpid(), child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL) && getppid()==parent);
        assert(dup2(application[0],STDIN_FILENO)==STDIN_FILENO);
        char scenario[8], protection[8];
        snprintf(scenario,sizeof(scenario),"%u",mode); snprintf(protection,sizeof(protection),"%u",protected);
        execl(runner,runner,"--store",path,"--user","0:0","--deadline-seconds","20",
            "--","/bin/test","--origin-send",scenario,protection,(char *)NULL);
        _exit(90);
    }
    close(application[0]); reap_child(child);
    struct md_ipc_origins *service=NULL; assert(!md_ipc_origins_new(o.worker,launch_id,&service));
    unsigned messages=mode==4 || mode==5 || mode==7 || mode==11 || mode==14 || (mode==15 && !(protected&4))
        ? 0 : mode==6 || mode==13 ? 2 : 1;
    for (unsigned n=0;n<messages;++n) {
        char byte; struct md_fs_rights rights={0};
        unsigned sender=mode!=9 && mode!=10 && (mode==8 || mode==13 || (protected&4));
        unsigned tokens=mode==9 || mode==10 || sender, data=mode!=10;
        assert(md_fs_receive(application[1],&byte,1,&rights)==1 && byte=='s' && rights.count==data+tokens);
        if (tokens) {
            struct work read={.operation=READ_MESSAGE,.sender_identity=sender,
                .fds={application[1],rights.fd[rights.count-1]}};
            submit(&o,&read); finish(&o,&read);
        }
        if (!data) { md_fs_close_rights(&rights); continue; }
        int fds[]={application[1],rights.fd[0]}; struct md_received_fds *input=captured(fds,2);
        md_fs_close_rights(&rights);
        struct transfer_result r={.owner=pthread_self()};
        assert(!md_ipc_origins_receive(service,input,transfer_ready,&r,&r.request));
        md_received_fds_release(input); transfer_wait(&o,&r);
        if (r.error) fprintf(stderr,"capsule send mode=%u protected=%u receive error=%d\n",mode,protected,r.error);
        assert(!r.error && !md_ipc_origins_take(&r.request,&r.received,NULL));
        unsigned count=mode ? 253 : 1;
        assert(r.received->count==count && r.received->origins);
        for (unsigned i=0;i<count;++i) {
            int fd=r.received->fds[i]; struct stat st;
            assert(r.received->origins[i] && !md_fs_anchor_check_fd(md_fs_anchor_from_object(r.received->origins[i]),fd) && !fstat(fd,&st));
            if (i%2) assert(S_ISDIR(st.st_mode) && (fcntl(fd,F_GETFL)&O_PATH));
            else assert(S_ISREG(st.st_mode) && lseek(fd,0,SEEK_CUR)==1 && pread(fd,&byte,1,1)==1 && byte=='e');
        }
        md_received_fds_release(r.received);
    }
    char byte; assert(!recv(application[1],&byte,1,MSG_DONTWAIT)); close(application[1]);
    owner_close(&o); md_ipc_origins_destroy(service); descriptor_balance(before,"guest send",mode,protected);
    int maintenance=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(maintenance>=0);
    assert(!flock(maintenance,LOCK_EX|LOCK_NB)); close(maintenance);
    printf("PASS guest capsule send mode=%u protected=%u: captured origins, task-local send and exact release\n",mode,protected);
}
void md_test_ipc_origin_guest(const char *path, const char *runner) {
    /* EVENT_WAIT: runner exit and final pipe EOF; the alarm bounds the fixture. */
    alarm(180); setbuf(stdout,NULL);
    for (unsigned protected=0;protected<2;++protected)
        for (unsigned mode=0;mode<16;++mode) transfer_guest_send(path,runner,mode,protected);
    for (unsigned protected=4;protected<6;++protected)
        for (unsigned mode=0;mode<16;++mode) transfer_guest_send(path,runner,mode,protected);
    for (unsigned protected=0;protected<6;++protected) for (unsigned mode=0;mode<15;++mode) {
        /* Batch broker IO and helper-specific in-flight quotas have their own
         * contracts. This matrix covers scalar receipt publication. */
        if ((protected&6) && (mode==3 || mode==9)) continue;
        unsigned before=descriptors(); struct transfer_fixture f;
        transfer_new(&f,path); transfer_prepare(&f);
        int rights[2]={f.result[0].received->fds[1],-1}, token=-1;
        unsigned count=1;
        if (mode>=10 && mode<=12) {
            struct work message={.operation=MESSAGE,.fds={f.application[0],-1}};
            submit(&f.owner[0],&message); finish(&f.owner[0],&message);
            token=message.fds[1]; count=2;
            rights[mode==10 ? 0 : 1]=token;
            rights[mode==10 ? 1 : 0]=f.result[0].received->fds[1];
            int passcred=mode!=12;
            assert(!setsockopt(f.application[1],SOL_SOCKET,SO_PASSCRED,&passcred,sizeof(passcred)));
        } else if (mode==13) {
            close(f.application[0]); close(f.application[1]);
            assert(!socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,f.application));
            struct work w={.operation=PAIR_NATIVE,.fds={f.application[0],f.application[1]}};
            submit(&f.owner[0],&w); finish(&f.owner[0],&w);
        }
        for (unsigned i=0;i<(mode==3 ? 2U : 1U);++i)
            assert(md_fs_send_fds(f.application[0],"s",1,rights,count)==1);
        if (token>=0) close(token);
        /* Only the kernel receive queue retains the capsule. The receiving
         * launch has neither the sender worker nor an origin object to borrow. */
        for (unsigned i=0;i<2;++i) {
            owner_close(&f.owner[i]); md_ipc_origins_destroy(f.service[i]); f.service[i]=NULL;
        }
        md_received_fds_release(f.result[0].received); f.result[0].received=NULL;
        close(f.application[0]); f.application[0]=-1;
        pid_t parent=getpid(), child=fork(); assert(child>=0);
        if (!child) {
            assert(!prctl(PR_SET_PDEATHSIG,SIGKILL) && getppid()==parent);
            assert(dup2(f.application[1],STDIN_FILENO)==STDIN_FILENO);
            char scenario[8], protection[8];
            snprintf(scenario,sizeof(scenario),"%u",mode);
            snprintf(protection,sizeof(protection),"%u",protected);
            execl(runner,runner,"--store",path,"--user","0:0","--deadline-seconds","20",
                "--","/bin/test","--origin-receive",scenario,protection,(char *)NULL);
            _exit(90);
        }
        close(f.application[1]); f.application[1]=-1;
        reap_child(child); transfer_close(&f); assert(descriptors()==before);
        int maintenance=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(maintenance>=0);
        assert(!flock(maintenance,LOCK_EX|LOCK_NB)); close(maintenance);
        printf("PASS guest capsule receive mode=%u protected=%u: publication and exact release\n",mode,protected);
    }
}
