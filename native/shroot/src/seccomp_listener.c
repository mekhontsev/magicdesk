#include "seccomp_listener.h"
#include "seccomp_notify.h"
#include "proc_file.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/magic.h>
#include <stdlib.h>
#include <sys/eventfd.h>
#include <unistd.h>

struct listener {
    struct md_fd_object object;
    struct md_seccomp_policy *policy;
};
static void destroy(struct md_fd_object *object) {
    struct listener *listener=(void *)object;
    md_seccomp_policy_release(listener->policy); free(listener);
}
static const struct md_fd_object_type type={.destroy=destroy};
struct md_fd_object *md_seccomp_listener_new(struct md_seccomp_policy *policy) {
    if (!policy) { errno=EINVAL; return NULL; }
    struct listener *listener=calloc(1,sizeof(*listener));
    if (!listener) return NULL;
    md_fd_object_init(&listener->object,&type);
    listener->policy=md_seccomp_policy_retain(policy);
    return &listener->object;
}
struct virtual_listener {
    atomic_uint references;
    struct md_seccomp_policy *policy;
    struct md_seccomp_notify *notifications;
    int carrier;
};
static void *virtual_retain(void *context) {
    struct virtual_listener *f=context;
    atomic_fetch_add_explicit(&f->references,1,memory_order_relaxed); return f;
}
static void virtual_release(void *context) {
    struct virtual_listener *f=context;
    if (atomic_fetch_sub_explicit(&f->references,1,memory_order_acq_rel)!=1) return;
    md_seccomp_notify_close(f->notifications); md_seccomp_notify_release(f->notifications);
    md_seccomp_policy_release(f->policy); close(f->carrier); free(f);
}
static int virtual_stat(void *context,struct stat *out) {
    return fstat(((struct virtual_listener *)context)->carrier,out) ? -errno : 0;
}
static int virtual_access(void *context,const struct md_identity *identity,unsigned mode) {
    (void)context; (void)identity; (void)mode; return 0;
}
static int virtual_descriptor(void *context,int flags) {
    (void)flags;
    int fd=fcntl(((struct virtual_listener *)context)->carrier,F_DUPFD_CLOEXEC,0);
    return fd<0 ? -errno : fd;
}
static struct md_control_file virtual_control(void *context) {
    return md_seccomp_notify_control(((struct virtual_listener *)context)->notifications);
}
static int virtual_spec(void *context,unsigned command,struct md_control_ioctl *out) {
    struct md_control_file file=virtual_control(context);
    return file.ops->ioctl_spec(file.context,command,out);
}
static long virtual_ioctl(void *context,const struct md_control_actor *actor,unsigned command,
        unsigned long value,void *data,size_t size,struct md_control_pending **pending) {
    struct md_control_file file=virtual_control(context);
    return file.ops->ioctl_async(file.context,actor,command,value,data,size,pending);
}
static long virtual_poll(void *context) {
    struct md_control_file file=virtual_control(context);
    return file.ops->poll(file.context);
}
static int virtual_poll_descriptor(void *context,int *out) {
    struct md_control_file file=virtual_control(context);
    return file.ops->poll_descriptor(file.context,out);
}
static const struct md_control_file_ops virtual_ops={.retain=virtual_retain,.release=virtual_release,
    .access=virtual_access,.stat=virtual_stat,.descriptor=virtual_descriptor,.nonseekable=1,.no_reopen=1,
    .filesystem=ANON_INODE_FS_MAGIC,
    .ioctl_spec=virtual_spec,.ioctl_async=virtual_ioctl,.poll=virtual_poll,.poll_descriptor=virtual_poll_descriptor};
static void virtual_activity(struct md_fd_object *object,int active) {
    struct md_seccomp_notify *q=md_seccomp_listener_notifications(object);
    md_seccomp_notify_active(q,active);
}
struct md_fd_object *md_seccomp_listener_virtual(struct md_seccomp_policy *policy,
        const struct md_control_opener *opener,struct md_resource_wakeup *wakeup) {
    if (!policy || !opener || !opener->identity) { errno=EINVAL; return NULL; }
    struct virtual_listener *f=calloc(1,sizeof(*f));
    if (!f) return NULL;
    atomic_init(&f->references,1); f->carrier=-1;
    f->policy=md_seccomp_policy_retain(policy);
    f->notifications=md_seccomp_notify_new(wakeup);
    if (f->notifications) f->carrier=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
    struct md_proc_file *file=f->carrier>=0
        ? md_proc_file_open_control(&virtual_ops,f,opener,O_RDWR|O_CLOEXEC) : NULL;
    int error=errno;
    if (file) {
        int bound=md_seccomp_policy_bind_listener(policy,md_proc_file_object(file),virtual_activity);
        if (bound) { error=-bound; md_proc_file_release(file); file=NULL; }
    }
    virtual_release(f); errno=error;
    return md_proc_file_object(file);
}
static struct virtual_listener *virtual_from_object(const struct md_fd_object *object) {
    struct md_control_file file=md_proc_file_control(md_proc_file_from_object((struct md_fd_object *)object));
    return file.ops==&virtual_ops ? file.context : NULL;
}
const struct md_seccomp_policy *md_seccomp_listener_policy(const struct md_fd_object *object) {
    if (object && object->type==&type) return ((const struct listener *)object)->policy;
    struct virtual_listener *file=virtual_from_object(object);
    return file ? file->policy : NULL;
}
struct md_seccomp_notify *md_seccomp_listener_notifications(struct md_fd_object *object) {
    struct virtual_listener *file=virtual_from_object(object);
    return file ? file->notifications : NULL;
}
