#include "notification_result.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <unistd.h>

static int listener=-1, install_error, valid_error, send_error, interrupted, expired, sends, installs, released;
int __real_ioctl(int,unsigned long,...);
int __wrap_ioctl(int fd,unsigned long request,...) {
    va_list args; va_start(args,request);
    void *argument=va_arg(args,void *); va_end(args);
    if (fd!=listener) return __real_ioctl(fd,request,argument);
    if (request==SECCOMP_IOCTL_NOTIF_ID_VALID) {
        if (interrupted) { --interrupted; errno=EINTR; return -1; }
        if (valid_error) { errno=valid_error; return -1; }
        errno=ENOENT; return expired ? -1 : 0;
    }
    if (request==SECCOMP_IOCTL_NOTIF_ADDFD) {
        ++installs;
        struct seccomp_notif_addfd *add=argument;
        assert(add->flags==SECCOMP_ADDFD_FLAG_SEND);
        errno=install_error; return install_error ? -1 : 42;
    }
    assert(request==SECCOMP_IOCTL_NOTIF_SEND);
    if (send_error) { ++sends; errno=send_error; return -1; }
    ++sends; errno=ENOENT; return expired ? -1 : 0;
}
static void release(struct md_fd_object *object) { (void)object; ++released; }
void md_test_notification_result(void) {
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    struct md_fd_object origin;
    const struct md_fd_object_type type={.destroy=release};
    md_fd_object_init(&origin,&type);
    int source=open("/dev/null",O_RDONLY|O_CLOEXEC); assert(source>=0);
    listener=dup(source); assert(listener>=0);
    struct seccomp_data call={.nr=SYS_openat,.instruction_pointer=0x1000,.args={1,2,3,4,5,6}};
    struct seccomp_notif pending={.id=17,.pid=42,.data=call},next=pending;
    next.id=18;
    assert(md_notification_rebind(listener,&pending,&next)==-EBUSY && pending.id==17);
    valid_error=EBADF;
    assert(md_notification_rebind(listener,&pending,&next)==-EBADF && pending.id==17);
    valid_error=0;
    expired=1;
    for (unsigned i=0;i<10;++i) {
        struct seccomp_notif wrong=next;
        if (i<6) wrong.data.args[i]^=1;
        else if (i==6) ++wrong.pid;
        else if (i==7) ++wrong.data.nr;
        else if (i==8) ++wrong.data.arch;
        else ++wrong.data.instruction_pointer;
        assert(md_notification_rebind(listener,&pending,&wrong)==-EINVAL && pending.id==17);
    }
    assert(md_notification_rebind(listener,&pending,&pending)==-EINVAL && pending.id==17);
    interrupted=1;
    assert(!md_notification_rebind(listener,&pending,&next) && pending.id==18 && !sends && !installs);
    assert(!interrupted);
    const int errors[]={0,ENOENT,EINPROGRESS,EBADF};
    for (unsigned i=0;i<sizeof(errors)/sizeof(*errors);++i) {
        expired=0; send_error=errors[i];
        assert(md_notification_retire(listener,19,123)==(i==0 ? 1 : i==3 ? -EBADF : 0));
    }
    assert(sends==4 && !installs); sends=0; send_error=0;
    expired=0;
    struct md_notification_result result={0};
    assert(!md_notification_result_queued(&result));
    assert(!md_notification_result_fd(&result,&call,files,source,&origin,O_CLOEXEC));
    assert(!md_notification_result_queued(&result));
    assert(md_notification_result_matches(&result,&call));
    for (unsigned i=0;i<6;++i) {
        call.args[i]^=1; assert(!md_notification_result_matches(&result,&call)); call.args[i]^=1;
    }
    install_error=ESRCH; expired=1;
    assert(md_notification_result_offer(&result,listener,1,NULL)==MD_NOTIFICATION_CANCELLED);
    assert(!result.delivered && result.source==source && !sends);
    install_error=0; expired=0;
    assert(md_notification_result_offer(&result,listener,2,NULL)==MD_NOTIFICATION_DELIVERED);
    assert(!md_notification_result_queued(&result));
    assert(result.value==42 && installs==2 && !sends);
    struct md_fd_object *published=md_fd_origins_object(files,42,NULL);
    assert(published==&origin); md_fd_object_release(published);
    assert(!md_notification_result_clear(&result));
    assert(!md_fd_origins_close_range(files,42,42,0));

    assert(!md_notification_result_fd(&result,&call,files,source,&origin,0));
    install_error=EMFILE;
    assert(md_notification_result_offer(&result,listener,3,NULL)==MD_NOTIFICATION_STATUS);
    assert(result.value==-EMFILE && !sends && installs==3);
    expired=1;
    assert(md_notification_result_reply(&result)==MD_NOTIFICATION_CANCELLED);
    expired=0;
    assert(md_notification_result_offer(&result,listener,4,NULL)==MD_NOTIFICATION_STATUS);
    assert(installs==3);
    assert(md_notification_result_reply(&result)==MD_NOTIFICATION_DELIVERED && result.value==-EMFILE);
    assert(!md_notification_result_clear(&result));

    struct md_fd_order busy={0};
    assert(md_fd_order_begin(files,&busy,NULL)==1 && !md_fd_order_start(&busy));
    assert(!md_notification_result_fd(&result,&call,files,source,&origin,0));
    install_error=0;
    assert(md_notification_result_offer(&result,listener,5,NULL)==MD_NOTIFICATION_WAIT);
    assert(md_notification_result_queued(&result));
    expired=1;
    assert(md_notification_result_progress(&result)==MD_NOTIFICATION_CANCELLED);
    assert(!md_notification_result_queued(&result));
    expired=0;
    assert(md_notification_result_offer(&result,listener,6,NULL)==MD_NOTIFICATION_WAIT);
    assert(md_notification_result_queued(&result));
    assert(!md_notification_result_clear(&result) && !md_fd_order_complete(&busy));
    assert(!md_notification_result_queued(&result));
    assert(installs==3 && sends==2 && !released && fcntl(source,F_GETFD)>=0);
    assert(!md_notification_result_status(&result,&call,-ENOENT));
    expired=1;
    assert(md_notification_result_offer(&result,listener,7,NULL)==MD_NOTIFICATION_STATUS);
    assert(md_notification_result_reply(&result)==MD_NOTIFICATION_CANCELLED);
    assert(result.active && !result.pending && !result.delivered && result.value==-ENOENT);
    expired=0;
    assert(md_notification_result_offer(&result,listener,8,NULL)==MD_NOTIFICATION_STATUS);
    assert(md_notification_result_reply(&result)==MD_NOTIFICATION_DELIVERED);
    assert(result.value==-ENOENT && installs==3 && sends==4);
    assert(!md_notification_result_clear(&result));
    md_fd_object_release(&origin); assert(released==1);
    md_fd_origins_release(files);
    assert(!close(listener) && !close(source)); listener=-1;
    puts("PASS notification result: exact cancelled-token replacement, queued ESRCH cancellation, retained FD, explicit scalar reply, retry and ownership");
}
