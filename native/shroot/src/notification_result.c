#include "notification_result.h"
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <sys/ioctl.h>

int md_notification_same_call(const struct seccomp_data *a,const struct seccomp_data *b) {
    return a->nr==b->nr && a->arch==b->arch && a->instruction_pointer==b->instruction_pointer
        && !memcmp(a->args,b->args,sizeof(a->args));
}
int md_notification_retire(int listener,uint64_t id,long value) {
    struct seccomp_notif_resp reply={.id=id,.val=value};
    if (!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply)) return 1;
    /* SEND arbitrates against concurrent ADDFD_SEND. A replied token can
     * remain present until the tracee consumes it, producing EINPROGRESS. */
    return errno==ENOENT || errno==EINPROGRESS ? 0 : -errno;
}
int md_notification_rebind(int listener,struct seccomp_notif *old,const struct seccomp_notif *next) {
    if (!old || !next || old->id==next->id || old->pid!=next->pid || !md_notification_same_call(&old->data,&next->data))
        return -EINVAL;
    int valid;
    do { valid=ioctl(listener,SECCOMP_IOCTL_NOTIF_ID_VALID,&old->id); } while (valid<0 && errno==EINTR);
    if (!valid) return -EBUSY;
    if (errno!=ENOENT) return -errno;
    *old=*next;
    return 0;
}
int md_notification_result_status(struct md_notification_result *r,const struct seccomp_data *call,long value) {
    if (!r || !call || r->active) return -EINVAL;
    *r=(struct md_notification_result){.call=*call,.source=-1,.active=1,.value=value};
    return 0;
}
int md_notification_result_fd(struct md_notification_result *r,const struct seccomp_data *call,
        struct md_fd_origins *files,int source,struct md_fd_object *origin,unsigned flags) {
    if (!files || source<0 || flags&~O_CLOEXEC) return -EINVAL;
    int error=md_notification_result_status(r,call,0);
    if (error) return error;
    r->source=source; r->files=md_fd_origins_pin(files);
    r->origin=md_fd_object_retain(origin); r->flags=flags;
    return 0;
}
int md_notification_result_matches(const struct md_notification_result *r,const struct seccomp_data *call) {
    return r && r->active && call && md_notification_same_call(&r->call,call);
}
int md_notification_result_queued(const struct md_notification_result *r) {
    return r && md_fd_order_state(&r->install.files)==MD_RESOURCE_ORDER_QUEUED;
}
int md_notification_result_progress(struct md_notification_result *r) {
    if (!r || !r->active || !r->pending || r->delivered) return -EINVAL;
    if (r->source>=0) {
        int value=md_fd_install_progress(&r->install);
        if (value==MD_FD_INSTALL_WAIT) return MD_NOTIFICATION_WAIT;
        if (r->install.retire_error) return r->install.retire_error;
        if (value==MD_FD_INSTALL_CANCELLED) { r->pending=0; return MD_NOTIFICATION_CANCELLED; }
        r->value=value;
        if (value>=0) { r->delivered=1; r->pending=0; return MD_NOTIFICATION_DELIVERED; }
        /* Installation failed. Retain this errno, not another installation
         * attempt if the following status reply is cancelled. */
        r->source=-1;
    }
    return MD_NOTIFICATION_STATUS;
}
int md_notification_result_reply(struct md_notification_result *r) {
    if (!r || !r->active || !r->pending || r->source>=0 || r->delivered) return -EINVAL;
    struct seccomp_notif_resp reply={.id=r->notification};
    if (r->value<0) reply.error=(int)r->value;
    else reply.val=r->value;
    int error=ioctl(r->listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) ? -errno : 0;
    r->pending=0;
    if (error) return error==-ENOENT ? MD_NOTIFICATION_CANCELLED : error;
    r->delivered=1; return MD_NOTIFICATION_DELIVERED;
}
int md_notification_result_offer(struct md_notification_result *r,int listener,uint64_t notification,
        struct md_resource_wakeup *wakeup) {
    if (!r || !r->active || r->delivered || listener<0) return -EINVAL;
    if (r->pending) {
        int valid;
        do { valid=ioctl(r->listener,SECCOMP_IOCTL_NOTIF_ID_VALID,&r->notification); } while (valid<0 && errno==EINTR);
        if (!valid) return -EBUSY;
        if (errno!=ENOENT) return -errno;
    }
    int error=0;
    if (r->source>=0) error=md_fd_install_begin(&r->install,r->files,listener,notification,
        r->source,r->origin,r->flags,wakeup);
    if (error) return error;
    r->listener=listener; r->notification=notification; r->pending=1;
    return md_notification_result_progress(r);
}
int md_notification_result_clear(struct md_notification_result *r) {
    if (!r) return -EINVAL;
    int error=md_fd_install_cancel(&r->install);
    if (error) return error;
    md_fd_origins_unpin(r->files); md_fd_object_release(r->origin);
    memset(r,0,sizeof(*r)); return 0;
}
