#define _GNU_SOURCE
#include "kmsg_device.h"
#include "file_permissions.h"
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <linux/magic.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/sysmacros.h>
#include <time.h>
#include <unistd.h>

enum { RECORDS=256, MESSAGE_MAX=1024 };
struct record { size_t size; char data[]; };
struct journal {
    atomic_uint references;
    pthread_mutex_t lock;
    struct reader *readers;
    uint64_t first,next;
    struct record *records[RECORDS];
};
struct reader {
    atomic_uint references;
    struct journal *journal;
    struct reader *next;
    struct md_resource_wakeup *wakeup;
    uint64_t sequence;
    int event;
};
static const struct md_control_file_ops operations;
static void *retain(void *context) {
    struct reader *r=context; atomic_fetch_add_explicit(&r->references,1,memory_order_relaxed); return r;
}
static void release(void *context) {
    struct reader *r=context;
    if (atomic_fetch_sub_explicit(&r->references,1,memory_order_acq_rel)!=1) return;
    struct journal *j=r->journal;
    if (r->event>=0) {
        pthread_mutex_lock(&j->lock);
        struct reader **at=&j->readers;
        while (*at && *at!=r) at=&(*at)->next;
        if (*at) *at=r->next;
        pthread_mutex_unlock(&j->lock);
        close(r->event);
    }
    md_resource_wakeup_release(r->wakeup);
    if (atomic_fetch_sub_explicit(&j->references,1,memory_order_acq_rel)==1) {
        for (unsigned i=0;i<RECORDS;++i) free(j->records[i]);
        pthread_mutex_destroy(&j->lock); free(j);
    }
    free(r);
}
static struct reader *reader_new(struct journal *j) {
    struct reader *r=calloc(1,sizeof(*r));
    if (r) {
        atomic_init(&r->references,1); r->journal=j; r->event=-1;
        atomic_fetch_add_explicit(&j->references,1,memory_order_relaxed);
    }
    return r;
}
int md_kmsg_device_node(struct md_control_file *out) {
    struct journal *j=calloc(1,sizeof(*j)); if (!j) return -ENOMEM;
    atomic_init(&j->references,0);
    int error=pthread_mutex_init(&j->lock,NULL);
    if (error) { free(j); return -error; }
    struct reader *r=reader_new(j);
    if (!r) { pthread_mutex_destroy(&j->lock); free(j); return -ENOMEM; }
    *out=(struct md_control_file){&operations,r}; return 0;
}
int md_kmsg_device_is_node(struct md_control_file f) {
    return f.ops==&operations && ((struct reader *)f.context)->event<0;
}
static void notify(struct reader *r) {
    uint64_t one=1;
    ssize_t result=write(r->event,&one,sizeof(one)); (void)result;
    md_resource_wakeup_signal(r->wakeup);
}
static void refresh(struct reader *r) {
    uint64_t value;
    while (read(r->event,&value,sizeof(value))>0) {}
    if (r->sequence<r->journal->next) notify(r);
}
static void *open_device(void *context,const struct md_control_opener *opener,int flags) {
    (void)opener;
    if (flags&O_DIRECTORY) { errno=ENOTDIR; return NULL; }
    struct journal *j=((struct reader *)context)->journal;
    struct reader *r=reader_new(j); if (!r) return NULL;
    r->event=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
    if (r->event<0) { int error=errno; release(r); errno=error; return NULL; }
    pthread_mutex_lock(&j->lock);
    r->sequence=j->first; r->next=j->readers; j->readers=r; refresh(r);
    pthread_mutex_unlock(&j->lock); return r;
}
static int metadata(void *context,struct stat *st) {
    (void)context;
    *st=(struct stat){.st_mode=S_IFCHR|0600,.st_nlink=1,.st_ino=11,
        .st_dev=makedev(0,99),.st_rdev=makedev(1,11),.st_blksize=4096}; return 0;
}
static int access_device(void *context,const struct md_identity *id,unsigned mode) {
    struct stat st; metadata(context,&st); return md_file_permissions(&st,mode,id);
}
static int descriptor(void *context,int flags) {
    struct reader *r=context;
    int fd=flags&O_PATH ? open("/dev/null",O_PATH|O_CLOEXEC) : fcntl(r->event,F_DUPFD_CLOEXEC,3);
    return fd<0 ? -errno : fd;
}
static long read_record(void *context,const struct md_control_actor *actor,void *data,size_t size,int64_t offset) {
    (void)offset; struct reader *r=context; struct journal *j=r->journal;
    pthread_mutex_lock(&j->lock);
    struct md_resource_wakeup *old=r->wakeup;
    r->wakeup=md_resource_wakeup_retain(actor ? actor->wakeup : NULL);
    md_resource_wakeup_release(old);
    long result;
    if (r->sequence<j->first) { r->sequence=j->first; result=-EPIPE; }
    else if (r->sequence==j->next) result=-EAGAIN;
    else {
        struct record *record=j->records[r->sequence%RECORDS];
        /* Linux consumes the record even for a short buffer or copy fault. */
        ++r->sequence;
        result=size<record->size ? -EINVAL : (long)record->size;
        if (result>=0) memcpy(data,record->data,record->size);
    }
    refresh(r);
    pthread_mutex_unlock(&j->lock); return result;
}
static long write_record(void *context,const struct md_control_actor *actor,const void *data,size_t size,int64_t offset) {
    (void)actor; (void)offset;
    if (size>MESSAGE_MAX) return -EINVAL;
    struct reader *r=context; struct journal *j=r->journal;
    const unsigned char *text=data; size_t length=size; unsigned priority=12;
    if (length && text[0]=='<') {
        size_t i=1; unsigned value=0;
        while (i<length && i<5 && text[i]>='0' && text[i]<='9') value=value*10+text[i++]-'0';
        if (i>1 && i<length && text[i]=='>' && value<=191) {
            priority=(value&~7U) ? value : value|8;
            text+=i+1; length-=i+1;
        }
    }
    if (length && text[length-1]=='\n') --length;
    struct record *record=malloc(sizeof(*record)+4*length+96); if (!record) return -ENOMEM;
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC,&now)) { int error=-errno; free(record); return error; }
    pthread_mutex_lock(&j->lock);
    size_t count=(size_t)sprintf(record->data,"%u,%" PRIu64 ",%" PRIu64 ",-;",priority,j->next,
        (uint64_t)now.tv_sec*1000000+(uint64_t)now.tv_nsec/1000);
    for (size_t i=0;i<length;++i) {
        if (text[i]<32 || text[i]>=127 || text[i]=='\\')
            count+=(size_t)sprintf(record->data+count,"\\x%02x",text[i]);
        else record->data[count++]=(char)text[i];
    }
    record->data[count++]='\n'; record->size=count;
    free(j->records[j->next%RECORDS]); j->records[j->next%RECORDS]=record;
    if (++j->next-j->first>RECORDS) ++j->first;
    for (struct reader *p=j->readers;p;p=p->next) notify(p);
    pthread_mutex_unlock(&j->lock); return (long)size;
}
static long seek_record(void *context,const struct md_control_actor *actor,int64_t offset,int whence,
        struct md_control_pending **pending) {
    (void)actor; (void)pending;
    if (offset) return -ESPIPE;
    if (whence!=SEEK_SET && whence!=SEEK_END && whence!=SEEK_DATA) return -EINVAL;
    struct reader *r=context; struct journal *j=r->journal;
    pthread_mutex_lock(&j->lock);
    r->sequence=whence==SEEK_END ? j->next : j->first; refresh(r);
    pthread_mutex_unlock(&j->lock); return 0;
}
static long poll_device(void *context) {
    struct reader *r=context; struct journal *j=r->journal;
    pthread_mutex_lock(&j->lock);
    long result=POLLOUT|(r->sequence<j->next ? POLLIN : 0)|(r->sequence<j->first ? POLLERR|POLLPRI : 0);
    pthread_mutex_unlock(&j->lock); return result;
}
static int readable(void *context) { return !!(poll_device(context)&POLLIN); }
static int poll_descriptor(void *context,int *out) { *out=((struct reader *)context)->event; return 0; }
static const struct md_control_file_ops operations={.retain=retain,.release=release,.stat=metadata,
    .access=access_device,.open=open_device,.descriptor=descriptor,.read=read_record,
    .write=write_record,.seek=seek_record,.filesystem=TMPFS_MAGIC,.readable=readable,
    .poll=poll_device,.poll_descriptor=poll_descriptor};
