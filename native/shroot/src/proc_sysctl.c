#define _GNU_SOURCE
#include "proc_sysctl.h"
#include "uts_namespace.h"
#include "task_resources.h"
#include "process_limits.h"
#include "fs_anchor.h"
#include "mount_policy.h"
#include "control_event.h"
#include "inode_watch.h"
#include "sysctl_value.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/magic.h>
#include <linux/memfd.h>
#include <poll.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

const char *md_proc_sysctl_name(enum md_proc_file_kind kind) {
    switch (kind) {
    case MD_PROC_FILE_HOSTNAME: return "hostname";
    case MD_PROC_FILE_DOMAINNAME: return "domainname";
    case MD_PROC_FILE_OSTYPE: return "ostype";
    case MD_PROC_FILE_OSRELEASE: return "osrelease";
    case MD_PROC_FILE_VERSION: return "version";
    case MD_PROC_FILE_OVERFLOWUID: return "overflowuid";
    case MD_PROC_FILE_OVERFLOWGID: return "overflowgid";
    case MD_PROC_FILE_CAP_LAST: return "cap_last_cap";
    case MD_PROC_FILE_NR_OPEN: return "nr_open";
    case MD_PROC_FILE_PID_MAX: return "pid_max";
    case MD_PROC_FILE_INOTIFY_INSTANCES: return "max_user_instances";
    case MD_PROC_FILE_INOTIFY_WATCHES: return "max_user_watches";
    case MD_PROC_FILE_INOTIFY_QUEUE: return "max_queued_events";
    default: return NULL;
    }
}
const char *md_proc_sysctl_directory(enum md_proc_file_kind kind) {
    if (kind==MD_PROC_FILE_NR_OPEN) return "sys/fs";
    return kind>=MD_PROC_FILE_INOTIFY_INSTANCES && kind<=MD_PROC_FILE_INOTIFY_QUEUE
        ? "sys/fs/inotify" : "sys/kernel";
}
static char *field(struct utsname *names, enum md_proc_file_kind kind) {
    switch (kind) {
    case MD_PROC_FILE_HOSTNAME: return names->nodename;
    case MD_PROC_FILE_DOMAINNAME: return names->domainname;
    case MD_PROC_FILE_OSTYPE: return names->sysname;
    case MD_PROC_FILE_OSRELEASE: return names->release;
    case MD_PROC_FILE_VERSION: return names->version;
    default: return NULL;
    }
}
static int inotify_limit(enum md_proc_file_kind kind) {
    return kind>=MD_PROC_FILE_INOTIFY_INSTANCES && kind<=MD_PROC_FILE_INOTIFY_QUEUE;
}
static int writable(enum md_proc_file_kind kind) {
    return kind<=MD_PROC_FILE_DOMAINNAME || inotify_limit(kind);
}
int md_proc_sysctl_access(enum md_proc_file_kind kind, const struct md_identity *id,
        unsigned mode, int effective) {
    if (!md_proc_sysctl_name(kind) || !id || mode&~7U) return -EINVAL;
    if (mode&X_OK) return -EACCES;
    /* Linux's UTS sysctl table uses global-root DAC, without a capability
     * override. sethostname has its separate namespace-scoped capability. */
    if ((mode&W_OK) && (!writable(kind)
            || (effective ? id->uid.effective : id->uid.real))) return -EACCES;
    return 0;
}
long md_proc_sysctl_read(const struct md_uts_namespace *uts, enum md_proc_file_kind kind,
        void *out, size_t capacity, int64_t offset) {
    if (offset<0) return -EINVAL;
    struct utsname names;
    char scalar[16];
    const char *text;
    if (kind>=MD_PROC_FILE_OVERFLOWUID && kind<=MD_PROC_FILE_INOTIFY_QUEUE) {
        unsigned value=MD_USER_OVERFLOW_ID;
        switch (kind) {
        case MD_PROC_FILE_CAP_LAST: value=MD_CAP_LAST; break;
        case MD_PROC_FILE_NR_OPEN: value=MD_PROCESS_NOFILE_MAX; break;
        case MD_PROC_FILE_PID_MAX: value=MD_PID_MAX; break;
        case MD_PROC_FILE_INOTIFY_INSTANCES: value=md_user_namespace_limit(NULL,MD_USER_INOTIFY_INSTANCES); break;
        case MD_PROC_FILE_INOTIFY_WATCHES: value=md_user_namespace_limit(NULL,MD_USER_INOTIFY_WATCHES); break;
        case MD_PROC_FILE_INOTIFY_QUEUE: value=md_inode_watch_queue_limit(); break;
        default: break;
        }
        snprintf(scalar,sizeof(scalar),"%u",value);
        text=scalar;
    } else {
        if (!uts) return -EINVAL;
        md_uts_namespace_read(uts,&names);
        text=field(&names,kind);
    }
    if (!text) return -EINVAL;
    size_t length=strlen(text);
    if ((uint64_t)offset>length || !capacity) return 0;
    length-=(size_t)offset;
    if (length>capacity) length=capacity;
    if (length) memcpy(out,text+offset,length);
    if (length<capacity) ((char *)out)[length++]='\n';
    return (long)length;
}
long md_proc_sysctl_write(struct md_uts_namespace *uts, enum md_proc_file_kind kind,
        const struct md_identity *writer, const void *data, size_t size, int64_t *offset) {
    if (!offset || *offset<0) return -EINVAL;
    if (md_proc_sysctl_access(kind,writer,W_OK,1)) return -EPERM;
    if (!size) return 0;
    int64_t next;
    if (size>INT64_MAX || __builtin_add_overflow(*offset,(int64_t)size,&next)) return -EINVAL;
    if (inotify_limit(kind)) {
        int error=0;
        if (!*offset) {
            uint32_t value=0;
            error=md_sysctl_parse_uint(data,size,&value);
            if (!error) error=kind==MD_PROC_FILE_INOTIFY_QUEUE ? md_inode_watch_set_queue_limit(value)
                : md_user_namespace_set_limit(NULL,kind==MD_PROC_FILE_INOTIFY_INSTANCES
                    ? MD_USER_INOTIFY_INSTANCES : MD_USER_INOTIFY_WATCHES,value);
        }
        if (!error) *offset=next;
        return error ? error : (long)size;
    }
    if (!uts) return -EINVAL;
    struct utsname names; md_uts_namespace_read(uts,&names);
    char *text=field(&names,kind);
    if ((uint64_t)*offset>strlen(text)) return (long)size;
    size_t position=(size_t)*offset;
    for (size_t i=0;i<size && position<sizeof(names.nodename)-1;++i) {
        char c=((const char *)data)[i];
        if (!c || c=='\n') break;
        text[position++]=c;
    }
    int error=md_uts_namespace_set(uts,kind==MD_PROC_FILE_DOMAINNAME,text,position);
    if (!error) *offset=next;
    return error ? error : (long)size;
}

int md_proc_sysctl_stat(enum md_proc_file_kind kind,struct stat *out) {
    if (!md_proc_sysctl_name(kind)) return -EINVAL;
    if (stat("/proc/sys/kernel",out)) return -errno;
    out->st_ino=UINT64_C(0xc000000000000000)|(unsigned)kind;
    out->st_mode=S_IFREG|(writable(kind) ? 0644 : 0444);
    out->st_nlink=1; out->st_rdev=0; out->st_size=0; out->st_blocks=0;
    out->st_uid=0; out->st_gid=0; return 0;
}
struct sysctl_file {
    atomic_uint references;
    enum md_proc_file_kind kind;
    struct md_fs_anchor *origin;
    atomic_int carrier;
    struct md_control_cursor changes;
};
static void *retain(void *context) {
    struct sysctl_file *f=context;
    atomic_fetch_add_explicit(&f->references,1,memory_order_relaxed); return f;
}
static void release(void *context) {
    struct sysctl_file *f=context;
    if (atomic_fetch_sub_explicit(&f->references,1,memory_order_acq_rel)!=1) return;
    md_control_cursor_close(&f->changes);
    int carrier=atomic_load_explicit(&f->carrier,memory_order_relaxed);
    if (carrier>=0) close(carrier);
    md_fs_anchor_release(f->origin); free(f);
}
static int metadata(void *context,struct stat *out) {
    return md_proc_sysctl_stat(((struct sysctl_file *)context)->kind,out);
}
static int access_value(void *context,const struct md_identity *id,unsigned mode) {
    struct sysctl_file *f=context;
    if ((mode&W_OK) && (md_fs_anchor_policy(f->origin)&MD_MOUNT_READONLY)) return -EROFS;
    return md_proc_sysctl_access(f->kind,id,mode,1);
}
static long read_value(void *context,const struct md_control_actor *actor,void *out,size_t size,int64_t offset) {
    struct sysctl_file *f=context;
    return md_proc_sysctl_read(actor->resources ? actor->resources->uts : NULL,
        f->kind,out,size,offset);
}
static long write_value(void *context,const struct md_control_actor *actor,const void *data,size_t size,int64_t offset) {
    struct sysctl_file *f=context;
    int error=access_value(f,actor->identity,W_OK);
    return error ? error : md_proc_sysctl_write(actor->resources ? actor->resources->uts : NULL,
        f->kind,actor->identity,data,size,&offset);
}
static int carrier(struct sysctl_file *f) {
    int current=atomic_load_explicit(&f->carrier,memory_order_acquire);
    if (current>=0) return current;
    int fd=(int)syscall(SYS_memfd_create,"sysctl",MFD_CLOEXEC|MFD_ALLOW_SEALING);
    if (fd<0) return -errno;
    if (fcntl(fd,F_ADD_SEALS,F_SEAL_SEAL|F_SEAL_SHRINK|F_SEAL_GROW|F_SEAL_WRITE)) {
        int error=-errno; close(fd); return error;
    }
    /* Placements/prototypes need no native file. The open description owns
     * the first published carrier; concurrent captures borrow that same OFD. */
    if (!atomic_compare_exchange_strong_explicit(&f->carrier,&current,fd,
            memory_order_acq_rel,memory_order_acquire)) close(fd);
    else current=fd;
    return current;
}
static int descriptor(void *context,int flags) {
    int backing=carrier(context);
    if (backing<0) return backing;
    if (!(flags&O_PATH)) {
        int fd=fcntl(backing,F_DUPFD_CLOEXEC,3);
        return fd<0 ? -errno : fd;
    }
    char path[64]; snprintf(path,sizeof(path),"/proc/self/fd/%d",backing);
    int fd=open(path,O_PATH|O_CLOEXEC);
    return fd<0 ? -errno : fd;
}
static struct md_control_file create(enum md_proc_file_kind,struct md_fs_anchor *,struct md_control_event *);
static void *at(void *context,struct md_fs_anchor *origin) {
    struct sysctl_file *old=context;
    if (old->origin==origin) return retain(old);
    return create(old->kind,origin,old->changes.source).context;
}
static struct md_fs_anchor *origin(void *context) {
    return ((struct sysctl_file *)context)->origin;
}
static void *open_value(void *context,const struct md_control_opener *opener,int flags) {
    (void)opener; (void)flags;
    struct sysctl_file *f=context;
    return create(f->kind,f->origin,f->changes.source).context;
}
static int poll_descriptor(void *context,int *descriptor) {
    struct sysctl_file *f=context;
    *descriptor=-1;
    if (!f->changes.source) return 0;
    int fd=md_control_cursor_watch(&f->changes);
    if (fd<0) return fd;
    *descriptor=fd; return 0;
}
static long poll_value(void *context) {
    struct sysctl_file *f=context;
    int changed=f->changes.source ? md_control_cursor_poll(&f->changes) : 0;
    if (changed<0) return changed;
    return changed ? POLLIN|POLLRDNORM|POLLERR|POLLPRI : POLLIN|POLLRDNORM|POLLOUT|POLLWRNORM;
}
static const struct md_control_file_ops ops={.retain=retain,.release=release,.stat=metadata,
    .access=access_value,.read=read_value,.write=write_value,.filesystem=PROC_SUPER_MAGIC,
    .at=at,.descriptor=descriptor,.origin=origin,.open=open_value,
    .poll_descriptor=poll_descriptor,.poll=poll_value};
enum md_proc_file_kind md_proc_sysctl_kind(const struct md_control_file_ops *provider,void *context) {
    return provider==&ops ? ((struct sysctl_file *)context)->kind : MD_PROC_FILE_CONTROL;
}
static struct md_control_file create(enum md_proc_file_kind kind,struct md_fs_anchor *origin,
        struct md_control_event *source) {
    if (!md_proc_sysctl_name(kind)) { errno=EINVAL; return (struct md_control_file){0}; }
    struct sysctl_file *f=calloc(1,sizeof(*f));
    if (!f) return (struct md_control_file){0};
    atomic_init(&f->references,1); atomic_init(&f->carrier,-1);
    f->kind=kind; f->origin=md_fs_anchor_retain(origin);
    md_control_cursor_open(&f->changes,source);
    return (struct md_control_file){&ops,f};
}
struct md_control_file md_proc_sysctl_new(enum md_proc_file_kind kind,struct md_fs_anchor *origin,
        const struct md_uts_namespace *uts) {
    return create(kind,origin,kind==MD_PROC_FILE_HOSTNAME || kind==MD_PROC_FILE_DOMAINNAME
        ? md_uts_namespace_event(uts,kind==MD_PROC_FILE_DOMAINNAME) : NULL);
}
