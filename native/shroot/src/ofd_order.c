#define _GNU_SOURCE
#include "ofd_order.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/kcmp.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef F_DUPFD_QUERY
#define F_DUPFD_QUERY 1027
#endif
enum comparison { UNKNOWN, QUERY, KCMP, INODE };
struct md_ofd_orders { atomic_uint refs; pthread_mutex_t lock; struct md_ofd_entry *first; enum comparison comparison; };
struct md_ofd_entry {
    struct md_ofd_entry *next;
    struct md_resource_queue queue;
    unsigned refs;
    int fd;
    dev_t device;
    ino_t inode;
};
static int comparison(struct md_ofd_orders *q,int fd) {
    if (q->comparison) return 0;
    int result=fcntl(fd,F_DUPFD_QUERY,fd);
    if (result==1) { q->comparison=QUERY; return 0; }
    if (result>=0) return -EIO;
    if (errno!=EINVAL && errno!=ENOSYS && errno!=EPERM && errno!=EACCES) return -errno;
    /* An inherited filter may kill kcmp instead of returning an errno. Never
     * probe it in a filtered owner. Coarser ordering preserves cursor safety;
     * it does not claim that separate opens share an open description. */
    if (prctl(PR_GET_SECCOMP,0,0,0,0)==0) {
        long same=syscall(SYS_kcmp,getpid(),getpid(),KCMP_FILE,fd,fd);
        if (!same) { q->comparison=KCMP; return 0; }
        if (same>=0) return -EIO;
        if (errno!=ENOSYS && errno!=EPERM && errno!=EACCES) return -errno;
    }
    q->comparison=INODE; return 0;
}
int md_ofd_orders_new(struct md_ofd_orders **out) {
    if (!out || *out) return -EINVAL;
    struct md_ofd_orders *q=calloc(1,sizeof(*q));
    if (!q) return -ENOMEM;
    int error=pthread_mutex_init(&q->lock,NULL);
    if (error) { free(q); return -error; }
    atomic_init(&q->refs,1); *out=q; return 0;
}
void md_ofd_orders_release(struct md_ofd_orders *q) {
    if (!q || atomic_fetch_sub_explicit(&q->refs,1,memory_order_acq_rel)!=1) return;
    pthread_mutex_destroy(&q->lock); free(q);
}
static void drop(struct md_ofd_order *op) {
    struct md_ofd_orders *q=op->owner;
    struct md_ofd_entry *entry=op->entry;
    pthread_mutex_lock(&q->lock);
    int last=!--entry->refs;
    if (last) {
        struct md_ofd_entry **p=&q->first;
        while (*p!=entry) p=&(*p)->next;
        *p=entry->next;
    }
    pthread_mutex_unlock(&q->lock);
    if (last) { close(entry->fd); md_resource_queue_destroy(&entry->queue); free(entry); }
    *op=(struct md_ofd_order){0};
    md_ofd_orders_release(q);
}
int md_ofd_order_begin(struct md_ofd_orders *q, int fd, int reserve,
        struct md_ofd_order *op, struct md_resource_wakeup *wake) {
    if (!q || !op || fd<0 || (reserve!=0 && reserve!=1)) return -EINVAL;
    if (op->owner || op->entry || op->order.queue) return -EBUSY;
    pthread_mutex_lock(&q->lock);
    struct md_ofd_entry *entry=q->first;
    int error=entry || reserve ? comparison(q,fd) : 0;
    struct stat st={0};
    if (!error && (entry || reserve) && q->comparison==INODE && fstat(fd,&st)) error=-errno;
    for (;!error && entry;entry=entry->next) {
        if (q->comparison==INODE) {
            if (entry->device==st.st_dev && entry->inode==st.st_ino) break;
        } else {
            long same=q->comparison==QUERY ? fcntl(fd,F_DUPFD_QUERY,entry->fd)
                : syscall(SYS_kcmp,getpid(),getpid(),KCMP_FILE,fd,entry->fd);
            if (same<0) { error=-errno; break; }
            if (q->comparison==QUERY ? same!=0 : same==0) break;
        }
    }
    if (!error && !entry && reserve) {
        entry=calloc(1,sizeof(*entry));
        if (!entry) error=-ENOMEM;
        else {
            entry->fd=fcntl(fd,F_DUPFD_CLOEXEC,0);
            entry->device=st.st_dev; entry->inode=st.st_ino;
            if (entry->fd<0) error=-errno;
            else error=md_resource_queue_init(&entry->queue);
            if (error) { if (entry->fd>=0) close(entry->fd); free(entry); entry=NULL; }
            else { entry->next=q->first; q->first=entry; }
        }
    }
    int ready=1;
    if (!error && entry) {
        atomic_fetch_add_explicit(&q->refs,1,memory_order_relaxed);
        ++entry->refs; op->entry=entry; op->owner=q;
        ready=md_resource_order_begin(&entry->queue,&op->order,wake);
    }
    pthread_mutex_unlock(&q->lock);
    if (ready<0) { drop(op); return ready; }
    return error ? error : ready;
}
enum md_resource_order_state md_ofd_order_state(const struct md_ofd_order *op) {
    return md_resource_order_state(op ? &op->order : NULL);
}
int md_ofd_order_start(struct md_ofd_order *op) {
    return op && op->owner ? md_resource_order_start(&op->order) : -EINVAL;
}
int md_ofd_order_cancel(struct md_ofd_order *op) {
    if (!op || !op->owner) return -EINVAL;
    int result=md_resource_order_cancel(&op->order);
    if (!op->order.queue) drop(op);
    return result;
}
int md_ofd_order_complete(struct md_ofd_order *op) {
    if (!op || !op->owner) return -EINVAL;
    int result=md_resource_order_complete(&op->order);
    if (!op->order.queue) drop(op);
    return result;
}
