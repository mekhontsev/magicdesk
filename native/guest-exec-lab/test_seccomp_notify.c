#define _GNU_SOURCE
#include "seccomp_notify.h"
#include "pid_namespace.h"
#include "task_resources.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <pthread.h>
#include <poll.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stddef.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static void native_path_lifetime(void) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getppid,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_USER_NOTIF),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        };
        struct sock_fprog program={sizeof(code)/sizeof(*code),code};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        int listener=syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_NEW_LISTENER,&program);
        assert(listener>=0);
        char path[64]; snprintf(path,sizeof(path),"/proc/self/fd/%d",listener);
        int metadata=open(path,O_PATH|O_CLOEXEC); assert(metadata>=0);
        struct stat before,after;
        assert(!fstat(listener,&before)); assert(!close(listener));
        errno=0; assert(syscall(SYS_getppid)==-1 && errno==ENOSYS);
        assert(!fstat(metadata,&after) && before.st_dev==after.st_dev && before.st_ino==after.st_ino);
        assert(!close(metadata)); _exit(0);
    }
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    puts("PASS native listener oracle: O_PATH preserves metadata without retaining notification authority");
}

struct worker { struct md_control_file file; atomic_uint *seen; };
static long call(struct md_control_file file,unsigned command,void *data,size_t size,
        struct md_control_pending **pending) {
    return file.ops->ioctl_async(file.context,NULL,command,0,data,size,pending);
}
static void *worker(void *context) {
    struct worker *w=context;
    for (unsigned i=0;i<64;++i) {
        struct seccomp_notif message={0}; struct md_control_pending *pending=NULL;
        assert(!call(w->file,SECCOMP_IOCTL_NOTIF_RECV,&message,sizeof(message),&pending));
        unsigned index=(unsigned)message.data.args[0]; assert(index<512);
        assert(atomic_fetch_add(&w->seen[index],1)==0);
        if (i&1) { pending->copied(pending,0); pending->dispose(pending); pending=NULL; }
        struct seccomp_notif_resp response={.id=message.id,.val=index+1};
        struct md_control_pending *send=NULL;
        assert(!call(w->file,SECCOMP_IOCTL_NOTIF_SEND,&response,sizeof(response),&send) && !send);
        if (pending) { pending->copied(pending,-EFAULT); pending->dispose(pending); }
    }
    return NULL;
}
static struct md_pid_identity *identity_in(struct md_pid_namespace *space,pid_t native) {
    struct md_pid_identity *id=NULL;
    assert(!md_pid_identity_prepare(space,&id));
    assert(!md_pid_identity_publish(id,native,native)); return id;
}
static void notification_pid_views(void) {
    struct md_pid_namespace *parent=md_pid_namespace_new(NULL,NULL);
    struct md_pid_namespace *child=md_pid_namespace_new(parent,NULL);
    struct md_pid_namespace *sibling=md_pid_namespace_new(NULL,NULL);
    assert(parent && child && sibling);
    struct md_pid_identity *outer=identity_in(parent,100),*source=identity_in(child,101);
    struct md_pid_identity *inner=identity_in(child,102),*other=identity_in(sibling,103);
    struct md_pid_identity *readers[]={NULL,outer,inner,other};
    const unsigned expected[]={101,2,1,0};
    struct md_seccomp_notify *q=md_seccomp_notify_new(NULL); assert(q);
    struct md_control_file file=md_seccomp_notify_control(q);
    struct md_seccomp_notification *request=NULL;
    const struct seccomp_data data={.nr=SYS_getpid,.instruction_pointer=0x1234,.args={17,18,19,20,21,22}};
    assert(!md_seccomp_notify_begin(q,101,source,&data,&request));
    for (unsigned i=0;i<4;++i) {
        struct md_task_resources resources={.pid=readers[i]};
        struct md_control_actor actor={.resources=&resources};
        struct md_control_pending *pending=NULL; struct seccomp_notif received={0};
        assert(!file.ops->ioctl_async(file.context,&actor,SECCOMP_IOCTL_NOTIF_RECV,0,
            &received,sizeof(received),&pending));
        assert(received.pid==expected[i] && !memcmp(&received.data,&data,sizeof(data)));
        pending->copied(pending,-EFAULT); pending->dispose(pending);
    }
    md_seccomp_notification_release(request); request=NULL;
    struct md_task_resources resources={.pid=inner};
    struct md_control_actor actor={.resources=&resources};
    struct md_control_pending *pending=NULL; struct seccomp_notif received={0};
    assert(file.ops->ioctl_async(file.context,&actor,SECCOMP_IOCTL_NOTIF_RECV,0,
        &received,sizeof(received),&pending)==-EINPROGRESS);
    resources.pid=other;
    assert(!md_seccomp_notify_begin(q,101,source,&data,&request));
    md_pid_identity_reap(source); md_pid_identity_release(source);
    assert(!pending->resume(pending,&received,sizeof(received)) && received.pid==1);
    pending->copied(pending,0); pending->dispose(pending);
    md_seccomp_notification_release(request); md_seccomp_notify_close(q); md_seccomp_notify_release(q);
    md_pid_identity_release(outer); md_pid_identity_release(inner); md_pid_identity_release(other);
    md_pid_namespace_release(child); md_pid_namespace_release(parent); md_pid_namespace_release(sibling);
    puts("PASS notification PID views: native, ancestor, child, invisible sibling and retained receiver namespace");
}
void md_test_seccomp_notify(void) {
    native_path_lifetime();
    notification_pid_views();
    struct md_seccomp_notify *q=md_seccomp_notify_new(NULL); assert(q);
    struct md_seccomp_notification *requests[512]={0};
    atomic_uint seen[512];
    for (unsigned i=0;i<512;++i) {
        atomic_init(&seen[i],0);
        assert(!md_seccomp_notify_begin(q,i+1,NULL,&(struct seccomp_data){.nr=SYS_getpid,.args={i}},&requests[i]));
    }
    struct worker w={.file=md_seccomp_notify_control(q),.seen=seen};
    pthread_t workers[8];
    for (unsigned i=0;i<8;++i) assert(!pthread_create(&workers[i],NULL,worker,&w));
    for (unsigned i=0;i<8;++i) assert(!pthread_join(workers[i],NULL));
    for (unsigned i=0;i<512;++i) {
        struct seccomp_notif_resp result;
        assert(atomic_load(&seen[i])==1 && md_seccomp_notification_result(requests[i],&result)==1);
        assert(result.val==i+1 && !result.error && !result.flags);
        md_seccomp_notification_release(requests[i]);
    }
    assert(w.file.ops->poll(w.file.context)==0);
    md_seccomp_notify_active(q,0);
    assert(w.file.ops->poll(w.file.context)==POLLHUP);
    md_seccomp_notify_close(q); md_seccomp_notify_release(q);
    for (unsigned order=0;order<3;++order) {
        q=md_seccomp_notify_new(NULL); assert(q);
        struct md_control_file file=md_seccomp_notify_control(q);
        struct md_seccomp_notification *request=NULL;
        assert(!md_seccomp_notify_begin(q,1,NULL,&(struct seccomp_data){.nr=SYS_getpid},&request));
        struct seccomp_notif received={0}; struct md_control_pending *pending=NULL;
        assert(!call(file,SECCOMP_IOCTL_NOTIF_RECV,&received,sizeof(received),&pending));
        if (order==0) md_seccomp_notification_release(request);
        if (order==1) { md_seccomp_notify_close(q); md_seccomp_notify_release(q); }
        pending->copied(pending,-EFAULT); pending->dispose(pending);
        if (order!=1) { md_seccomp_notify_close(q); md_seccomp_notify_release(q); }
        if (order!=0) md_seccomp_notification_release(request);
    }
    puts("PASS virtual notifications: eight concurrent receivers, 512 exact replies, SEND/copy-ack races, cancellation and filter-user HUP");
}
#ifdef MD_NOTIFY_TEST_MAIN
int main(void) { md_test_seccomp_notify(); return 0; }
#endif
