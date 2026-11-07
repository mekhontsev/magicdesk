#include "fd_install.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/seccomp.h>
#include <sys/ioctl.h>

int md_fd_install_begin(struct md_fd_install *p, struct md_fd_origins *files,
        int listener, uint64_t notification, int source, struct md_fd_object *origin,
        unsigned flags, struct md_resource_wakeup *wakeup) {
    if (!p || !files || listener<0 || source<0 || flags&~O_CLOEXEC) return -EINVAL;
    if (p->borrowed) return -EBUSY;
    if (p->files.table) {
        if (p->files.table!=files || p->listener!=listener || p->source!=source
                || p->origin!=origin || p->flags!=flags || p->notification==notification) return -EBUSY;
        if (!ioctl(listener,SECCOMP_IOCTL_NOTIF_ID_VALID,&p->notification)) return -EBUSY;
        if (errno!=ENOENT) return -errno;
        /* A restarted admission can precede the queued turn's wakeup. Retire
         * only its expired notification; the caller still owns this result. */
        int error=md_fd_install_cancel(p);
        if (error) return error;
    }
    *p=(struct md_fd_install){.listener=listener,.notification=notification,
        .source=source,.origin=md_fd_object_retain(origin),.flags=flags,.add_flags=SECCOMP_ADDFD_FLAG_SEND};
    int result=md_fd_order_begin(files,&p->files,wakeup);
    if (result<0) { md_fd_object_release(p->origin); p->origin=NULL; }
    return result<0 ? result : 0;
}
int md_fd_install_borrow(struct md_fd_install *p,struct md_fd_order *files,
        int listener,uint64_t notification,int source,struct md_fd_object *origin,unsigned flags) {
    if (!p || !files || md_fd_order_state(files)!=MD_RESOURCE_ORDER_RUNNING
            || listener<0 || source<0 || flags&~O_CLOEXEC) return -EINVAL;
    if (p->files.table || p->borrowed) return -EBUSY;
    *p=(struct md_fd_install){.borrowed=files,.listener=listener,.notification=notification,
        .source=source,.origin=md_fd_object_retain(origin),.flags=flags,.add_flags=SECCOMP_ADDFD_FLAG_SEND};
    return 0;
}
int md_fd_install_target(struct md_fd_install *p,unsigned flags,unsigned number) {
    if (!p || (!p->files.table && !p->borrowed) || p->loan || flags&~(SECCOMP_ADDFD_FLAG_SEND|SECCOMP_ADDFD_FLAG_SETFD)
            || (!(flags&SECCOMP_ADDFD_FLAG_SETFD) && number)) return -EINVAL;
    p->add_flags=flags; p->number=number; return 0;
}
int md_fd_install_loan(struct md_fd_install *p,struct md_fd_loan *loan) {
    if (!p || (!p->files.table && !p->borrowed) || !loan || p->loan || !(p->flags&O_CLOEXEC)) return -EINVAL;
    p->loan=loan;
    return 0;
}
int md_fd_install_progress(struct md_fd_install *p) {
    if (!p || (!p->files.table && !p->borrowed)) return -EINVAL;
    struct md_fd_order *files=p->borrowed ? p->borrowed : &p->files;
    if (!p->borrowed && md_fd_order_state(files)!=MD_RESOURCE_ORDER_READY) {
        int valid;
        do { valid=ioctl(p->listener,SECCOMP_IOCTL_NOTIF_ID_VALID,&p->notification); } while (valid<0 && errno==EINTR);
        if (!valid) return MD_FD_INSTALL_WAIT;
        int result=errno==ENOENT ? MD_FD_INSTALL_CANCELLED : -errno;
        /* An interrupted receiver can restart before preceding table work
         * finishes. Retire its cancelled publication without that table turn. */
        p->retire_error=md_fd_install_cancel(p);
        return result;
    }
    int result=p->borrowed ? md_fd_order_state(files)==MD_RESOURCE_ORDER_RUNNING ? 0 : -EINVAL
        : md_fd_order_start(files);
    if (result) return result;
    struct md_fd_origin_update update={0};
    result=md_fd_origins_prepare_object(files->table,p->origin,p->flags&O_CLOEXEC ? FD_CLOEXEC : 0,&update);
    if (!result && p->loan) result=md_fd_origins_prepare_loan(&update,p->loan);
    if (!result) {
        struct seccomp_notif_addfd add={.id=p->notification,.flags=p->add_flags,
            .srcfd=p->source,.newfd=p->number,.newfd_flags=p->flags};
        result=ioctl(p->listener,SECCOMP_IOCTL_NOTIF_ADDFD,&add);
        if (result<0) result=errno==ENOENT ? MD_FD_INSTALL_CANCELLED : -errno;
        if (result==-ESRCH) {
            /* Cancellation after queuing ADDFD reports ESRCH when the receiver
             * discards that queue. Confirm expiry instead of exposing it as an
             * open failure after the provider already performed a mutation. */
            int valid;
            do { valid=ioctl(p->listener,SECCOMP_IOCTL_NOTIF_ID_VALID,&p->notification); } while (valid<0 && errno==EINTR);
            if (valid<0 && errno==ENOENT) result=MD_FD_INSTALL_CANCELLED;
        }
    }
    if (p->add_flags&SECCOMP_ADDFD_FLAG_SETFD) md_fd_origins_finish_closed(&update,result,&p->closed);
    else md_fd_origins_finish(&update,result);
    p->retire_error=p->borrowed ? 0 : md_fd_order_complete(files);
    p->borrowed=NULL;
    md_fd_object_release(p->origin); p->origin=NULL;
    return result;
}
int md_fd_install_cancel(struct md_fd_install *p) {
    if (!p) return -EINVAL;
    p->borrowed=NULL;
    int result=p->files.table ? md_fd_order_cancel(&p->files) : 0;
    if (result==1) result=md_fd_order_complete(&p->files);
    if (!p->files.table) { md_fd_object_release(p->origin); p->origin=NULL; }
    return result;
}
