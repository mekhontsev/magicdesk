#define _GNU_SOURCE
#include "proc_file.h"
#include "proc_sysctl.h"
#include "proc_namespace.h"
#include "pid_namespace.h"
#include "fd_selector.h"
#include "cgroup.h"
#include "task_resources.h"
#include "process_limits.h"
#include "file_permissions.h"
#include "directory_record.h"
#include "fs_anchor.h"
#include "mount_policy.h"
#include <linux/magic.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/mman.h>
#include <unistd.h>

struct md_proc_file {
    struct md_fd_object object;
    struct md_user_namespace *target;
    struct md_identity opener;
    struct md_identity process_identity;
    pid_t tracer;
    int process_state;
    struct md_process_limits limits;
    int limits_valid;
    enum md_proc_file_kind kind;
    atomic_int flags;
    int64_t position;
    struct md_resource_queue operations;
    struct md_proc_file_node node;
    void *directory;
    size_t directory_size, directory_capacity;
    struct md_pid_namespace *pid_view;
    struct md_pid_namespace *process_pid_namespace;
    char *content;
    size_t content_size;
    const struct md_control_file_ops *control;
    void *control_context;
    int control_directory;
    struct md_cgroup_member *cgroup;
    struct md_fs_anchor *placement;
    struct stat metadata;
};
static const struct md_control_file_ops task_ops;
static void destroy(struct md_fd_object *object) {
    struct md_proc_file *f=(void *)object;
    if (f->control) f->control->release(f->control_context);
    md_fs_anchor_release(f->placement);
    md_cgroup_member_release(f->cgroup);
    md_resource_queue_destroy(&f->operations);
    md_pid_namespace_release(f->pid_view); free(f->content);
    md_pid_namespace_release(f->process_pid_namespace);
    if (f->process_state) md_identity_release(&f->process_identity);
    md_user_namespace_release(f->target); md_identity_release(&f->opener); free(f->directory); free(f);
}
static unsigned selectors(const struct md_fd_object *object) {
    const struct md_proc_file *f=(const void *)object;
    return f->control && f->control->poll && !(atomic_load_explicit(&f->flags,memory_order_relaxed)&O_PATH)
        ? (1U<<MD_FD_SELECTOR_EPOLL_TARGET)|(1U<<MD_FD_SELECTOR_POLL) : 0;
}
static struct md_control_file readiness(struct md_fd_object *object) {
    return md_proc_file_control((void *)object);
}
struct md_fs_anchor *md_proc_file_origin(const struct md_proc_file *f) {
    if (!f) return NULL;
    return f->placement ? f->placement
        : f->control && f->control->origin ? f->control->origin(f->control_context) : NULL;
}
static struct md_fs_anchor *origin(struct md_fd_object *object) {
    struct md_proc_file *f=(void *)object;
    /* A device's anonymous IO carrier is not its path inode. Only providers
     * with a path-backed descriptor may export placement as FD authority. */
    return f->control ? (f->control->origin ? f->control->origin(f->control_context) : NULL)
        : f->placement;
}
static long closed(struct md_fd_object *object,const struct md_control_actor *actor,struct md_control_pending **pending) {
    struct md_proc_file *f=(void *)object;
    return f->control && f->control->flush && !(md_proc_file_flags(f)&O_PATH)
        ? f->control->flush(f->control_context,actor,pending) : 0;
}
static const struct md_fd_object_type type={.destroy=destroy,.selectors=1U<<MD_FD_SELECTOR_PROC,
    .selector_mask=selectors,.readiness=readiness,.origin=origin,.close=closed};
struct md_fd_object *md_proc_file_control_object(const struct md_control_file_ops *ops,void *context,
        const struct md_control_opener *opener,int flags,struct md_fs_anchor *placement,int *descriptor) {
    struct md_proc_file *file=md_proc_file_open_control(ops,context,opener,flags);
    if (!file) return NULL;
    if (ops->descriptor && !(flags&O_PATH)) {
        int fd=md_proc_file_descriptor(file);
        if (fd<0) { md_proc_file_release(file); errno=-fd; return NULL; }
        *descriptor=fd;
    }
    if (placement) md_proc_file_place(file,placement,&file->metadata);
    return md_proc_file_object(file);
}
struct md_fd_object *md_proc_file_object(struct md_proc_file *f) {
    return f ? &f->object : NULL;
}
struct md_proc_file *md_proc_file_from_object(struct md_fd_object *o) {
    return o && o->type==&type ? (void *)o : NULL;
}
struct md_proc_file *md_proc_file_open(struct md_user_namespace *target,
        enum md_proc_file_kind kind, const struct md_identity *opener, int flags) {
    int error=!opener || kind<MD_PROC_FILE_UID_MAP || kind>MD_PROC_FILE_CONTROL ? EINVAL : 0;
    int directory=md_proc_file_directory(kind);
    if (!error && (flags&O_DIRECTORY) && !directory) error=ENOTDIR;
    if (!error && directory && !(flags&O_PATH) && ((flags&O_ACCMODE)!=O_RDONLY || (flags&O_TRUNC))) error=EISDIR;
    if (!error && (flags&(O_CREAT|O_EXCL))==(O_CREAT|O_EXCL)) error=EEXIST;
    if (!error && !(flags&O_PATH) && (flags&O_ACCMODE)==O_ACCMODE) error=EINVAL;
    if (!error && !(flags&O_PATH) && (md_proc_file_namespace(kind) || md_proc_file_process(kind) || md_proc_file_cgroup_kind(kind))
            && ((flags&O_ACCMODE)!=O_RDONLY || (flags&O_TRUNC))) error=EACCES;
    if (!error && !(flags&O_PATH) && (flags&O_ACCMODE)!=O_RDONLY && kind==MD_PROC_FILE_SETGROUPS
            && !md_identity_capable_in(opener,target,CAP_SYS_ADMIN)) error=EACCES;
    if (!error && !(flags&O_PATH) && md_proc_file_sysctl(kind))
        error=-md_proc_sysctl_access(kind,opener,(flags&O_ACCMODE)!=O_RDONLY || (flags&O_TRUNC) ? W_OK : R_OK,1);
    if (error) { errno=error; return NULL; }
    struct md_proc_file *f=calloc(1,sizeof(*f));
    if (!f) return NULL;
    error=md_resource_queue_init(&f->operations);
    if (error) { free(f); errno=-error; return NULL; }
    md_fd_object_init(&f->object,&type);
    f->target=md_proc_file_sysctl(kind) || md_proc_file_namespace(kind) || directory || md_proc_file_process(kind)
        ? NULL : md_user_namespace_retain(target);
    f->opener=md_identity_copy(opener); f->kind=kind; atomic_init(&f->flags,flags);
    return f;
}
struct md_proc_file *md_proc_file_open_control(const struct md_control_file_ops *ops,void *context,
        const struct md_control_opener *opener,int flags) {
    if (!opener || !opener->identity) { errno=EINVAL; return NULL; }
    const struct md_identity *id=opener->identity;
    if (ops==&task_ops) return md_proc_file_reopen(context,opener,flags);
    int mode=(flags&O_PATH) ? 0 : (flags&O_ACCMODE)==O_RDWR ? R_OK|W_OK
        : (flags&O_ACCMODE)==O_WRONLY ? W_OK : R_OK;
    if (!(flags&O_PATH) && (flags&O_TRUNC)) mode|=W_OK;
    int error=!ops || !context ? -EINVAL : ops->access(context,id,(unsigned)mode);
    if (!error && (flags&O_NOATIME) && !(flags&O_PATH)) {
        struct stat st;
        error=ops->stat(context,&st);
        if (!error) error=md_file_noatime(&st,id);
    }
    if (error) { errno=-error; return NULL; }
    const struct md_namespace_reference *ref=ops->namespace_reference ? ops->namespace_reference(context) : NULL;
    struct stat metadata;
    int directory=0;
    if (ops->io) {
        error=ops->stat(context,&metadata);
        if (!error) directory=S_ISDIR(metadata.st_mode);
        if (!error && (flags&O_DIRECTORY) && !directory) error=-ENOTDIR;
        if (!error && directory && !(flags&O_PATH) && ((flags&O_ACCMODE)!=O_RDONLY || (flags&O_TRUNC))) error=-EISDIR;
        if (error) { errno=-error; return NULL; }
    }
    struct md_proc_file *f=md_proc_file_open(NULL,ref
        ? (enum md_proc_file_kind)(MD_PROC_FILE_NS_USER+ref->kind) : md_proc_sysctl_kind(ops,context),id,
        directory ? flags&~O_DIRECTORY : flags);
    if (f) {
        void *opened=ops->open && !(flags&O_PATH) ? ops->open(context,opener,flags) : ops->retain(context);
        if (!opened) { int saved=errno; md_proc_file_release(f); errno=saved; return NULL; }
        f->control=ops; f->control_context=opened;
        f->control_directory=directory; atomic_store_explicit(&f->flags,flags,memory_order_relaxed);
    }
    return f;
}
int md_proc_file_control_access(const struct md_proc_file *f,const struct md_identity *id,unsigned mode) {
    return f && f->control ? f->control->access(f->control_context,id,mode) : -EINVAL;
}
int md_proc_file_control_stat(const struct md_proc_file *f,struct stat *st) {
    return f && f->control ? f->control->stat(f->control_context,st) : -EINVAL;
}
struct md_control_file md_proc_file_control(const struct md_proc_file *f) {
    if (!f) return (struct md_control_file){0};
    return f->control ? (struct md_control_file){f->control,f->control_context}
        : md_proc_file_task(f->kind) ? (struct md_control_file){&task_ops,(void *)f}
        : (struct md_control_file){0};
}
unsigned long md_proc_file_filesystem(const struct md_proc_file *f) {
    return f && f->control ? f->control->filesystem : 0;
}
int md_proc_file_mapping(const struct md_proc_file *f,unsigned protection,unsigned flags) {
    if (!f || !f->control || (!f->control->mappable && !f->control->mapping)) return -ENODEV;
    if (f->flags&O_PATH) return -EBADF;
    if ((f->flags&O_ACCMODE)==O_WRONLY) return -EACCES;
    return (flags&MAP_SHARED) && (protection&PROT_WRITE) ? -EACCES : 0;
}
int md_proc_file_fcntl(struct md_proc_file *f,int command,unsigned long argument,long *out) {
    if (!f) return 0;
    int flags=atomic_load_explicit(&f->flags,memory_order_relaxed);
    if (command==F_GETFL) *out=flags&~(O_CLOEXEC|O_CREAT|O_EXCL|O_TRUNC);
    else if (flags&O_PATH) *out=-EBADF;
    else if (command==F_GET_SEALS) *out=f->control && f->control->seals ? f->control->seals(f->control_context) : -EINVAL;
    else if (command==F_ADD_SEALS && (flags&O_ACCMODE)==O_RDONLY) *out=-EPERM;
    else if (command==F_SETFL) {
        unsigned mutable=O_APPEND|O_NONBLOCK|O_NOATIME;
        if (argument&(O_DIRECT|O_ASYNC)) *out=-EOPNOTSUPP;
        else {
            atomic_store_explicit(&f->flags,(flags&~mutable)|(argument&mutable),memory_order_relaxed);
            *out=0;
        }
    } else return 0;
    return 1;
}
struct md_proc_file *md_proc_file_open_namespace(const struct md_namespace_reference *ref,
        const struct md_identity *opener, int flags) {
    struct md_control_file control=md_namespace_file_new(ref);
    if (!control.ops) return NULL;
    struct md_proc_file *f=md_proc_file_open_control(control.ops,control.context,
        &(struct md_control_opener){.identity=opener},flags);
    md_control_file_release(control);
    return f;
}
const struct md_namespace_reference *md_proc_file_namespace_reference(const struct md_proc_file *f) {
    return f && f->control && f->control->namespace_reference
        ? f->control->namespace_reference(f->control_context) : NULL;
}
struct md_proc_file *md_proc_file_retain(struct md_proc_file *f) {
    md_fd_object_retain(md_proc_file_object(f));
    return f;
}
void md_proc_file_release(struct md_proc_file *f) {
    md_fd_object_release(md_proc_file_object(f));
}
int md_proc_file_flags(const struct md_proc_file *f) { return f->flags; }
void md_proc_file_node(struct md_proc_file *f, struct md_proc_file_node node) { f->node=node; }
void md_proc_file_pid_view(struct md_proc_file *f, struct md_pid_namespace *view) {
    struct md_pid_namespace *old=f->pid_view;
    f->pid_view=md_pid_namespace_retain(view); md_pid_namespace_release(old);
}
void md_proc_file_process_state(struct md_proc_file *f,const struct md_identity *identity,
        struct md_pid_namespace *space,pid_t tracer,const struct md_process_limits *limits) {
    if (!md_proc_file_process(f->kind) || f->content) return;
    if (f->process_state) md_identity_release(&f->process_identity);
    f->process_identity=md_identity_copy(identity); f->process_state=1; f->tracer=tracer;
    f->limits_valid=limits!=NULL;
    if (limits) f->limits=*limits;
    struct md_pid_namespace *old=f->process_pid_namespace;
    f->process_pid_namespace=md_pid_namespace_retain(space); md_pid_namespace_release(old);
}
void md_proc_file_cgroup(struct md_proc_file *f,struct md_cgroup_member *member) {
    struct md_cgroup_member *old=f->cgroup;
    f->cgroup=md_cgroup_member_retain(member); md_cgroup_member_release(old);
}
struct md_proc_file_node md_proc_file_get_node(const struct md_proc_file *f) { return f->node; }
enum md_proc_file_kind md_proc_file_kind(const struct md_proc_file *f) { return f->kind; }
int md_proc_file_filesystem_paths(const struct md_proc_file *f) {
    return f && f->control && f->control->filesystem_paths;
}
struct md_proc_file *md_proc_file_reopen(const struct md_proc_file *f,
        const struct md_control_opener *opener, int flags) {
    if (!opener || !opener->identity) { errno=EINVAL; return NULL; }
    if (f->placement && !f->control && !(flags&O_PATH)) {
        unsigned mode=(flags&O_ACCMODE)==O_RDWR ? R_OK|W_OK : (flags&O_ACCMODE)==O_WRONLY ? W_OK : R_OK;
        if (flags&O_TRUNC) mode|=W_OK;
        int error=(mode&W_OK) && (md_fs_anchor_policy(f->placement)&MD_MOUNT_READONLY) ? -EROFS
            : md_proc_file_task_access(opener->identity,&f->metadata,mode,1);
        if (error) { errno=-error; return NULL; }
    }
    struct md_proc_file *next=f->control ? md_proc_file_open_control(f->control,f->control_context,opener,flags)
        : md_proc_file_open(f->target,f->kind,opener->identity,flags);
    if (next) {
        next->node=f->node; md_proc_file_pid_view(next,f->pid_view); md_proc_file_cgroup(next,f->cgroup);
        md_proc_file_place(next,f->placement,&f->metadata);
    }
    return next;
}
void md_proc_file_place(struct md_proc_file *f,struct md_fs_anchor *placement,const struct stat *metadata) {
    struct md_fs_anchor *old=f->placement;
    f->placement=md_fs_anchor_retain(placement); f->metadata=*metadata;
    md_fs_anchor_release(old);
}
int md_proc_file_task_stat(int pid,const struct md_identity *identity,enum md_proc_file_kind kind,
        struct md_proc_file_node *node,struct stat *value) {
    char path[64];
    snprintf(path,sizeof(path),"/proc/%d/%s",pid,md_proc_file_process_name(kind));
    if (stat(path,value)) return -errno;
    *node=(struct md_proc_file_node){value->st_dev,value->st_ino,pid};
    int protected=value->st_uid!=getuid();
    uint32_t uid=identity->uid.effective, gid=identity->gid.effective;
    if (protected) {
        uid=md_user_namespace_into(identity->user_namespace,0,0);
        gid=md_user_namespace_into(identity->user_namespace,1,0);
        if (uid==UINT32_MAX) uid=0;
        if (gid==UINT32_MAX) gid=0;
    }
    if (!md_proc_file_process(kind))
        value->st_ino=((uint64_t)value->st_ino<<2)|((unsigned)kind+1)|UINT64_C(0x8000000000000000);
    value->st_mode=S_IFREG|(md_proc_file_process(kind) || md_proc_file_cgroup_kind(kind) ? 0444 : 0644);
    value->st_nlink=1; value->st_rdev=0; value->st_size=0; value->st_blocks=0; value->st_blksize=getpagesize();
    value->st_uid=uid; value->st_gid=gid; return 0;
}
int md_proc_file_task_access(const struct md_identity *id,const struct stat *st,unsigned mode,int effective) {
    if (mode&~7U) return -EINVAL;
    if ((mode&X_OK) && !S_ISDIR(st->st_mode)) return -EACCES;
    if ((mode&W_OK) && S_ISDIR(st->st_mode)) return -EACCES;
    struct md_identity actor=md_identity_access(id,effective);
    return md_file_permissions(st,mode,&actor);
}
static void *task_retain(void *context) { return md_proc_file_retain(context); }
static void task_release(void *context) { md_proc_file_release(context); }
static struct md_fs_anchor *task_origin(void *context) { return ((struct md_proc_file *)context)->placement; }
static int task_stat(void *context,struct stat *st) {
    struct md_proc_file *f=context;
    if (f->placement) *st=f->metadata;
    else {
        *st=(struct stat){.st_dev=f->node.device,.st_ino=f->node.inode,.st_mode=S_IFREG|0444,.st_nlink=1};
        if (!md_proc_file_process(f->kind)) st->st_ino=(f->node.inode<<2)|((unsigned)f->kind+1)|UINT64_C(0x8000000000000000);
    }
    return 0;
}
static int task_access(void *context,const struct md_identity *id,unsigned mode) {
    struct md_proc_file *f=context;
    if ((mode&W_OK) && (md_fs_anchor_policy(f->placement)&MD_MOUNT_READONLY)) return -EROFS;
    struct stat st; task_stat(f,&st);
    return md_proc_file_task_access(id,&st,mode,1);
}
static const struct md_control_file_ops task_ops={.retain=task_retain,.release=task_release,
    .origin=task_origin,.stat=task_stat,.access=task_access,.filesystem=PROC_SUPER_MAGIC};
int md_proc_file_descriptor(const struct md_proc_file *f) {
    if (!f) return -EINVAL;
    if (f->control && f->control->descriptor) return f->control->descriptor(f->control_context,f->flags);
    if (md_proc_file_directory(f->kind)) {
        char path[64]; snprintf(path,sizeof(path),"/proc/%d%s",f->node.process,
            f->kind==MD_PROC_FILE_NS_DIRECTORY ? "/ns" : "");
        int fd=open(path,O_CLOEXEC|(f->flags&~(O_CREAT|O_EXCL|O_TRUNC|O_NOATIME)));
        if (fd<0) return -errno;
        struct stat st;
        int error=fstat(fd,&st) ? -errno : st.st_dev!=f->node.device || st.st_ino!=f->node.inode ? -ENOENT : 0;
        if (error) close(fd);
        return error ? error : fd;
    }
    /* An inert native carrier supplies access modes without requiring host
     * CONFIG_USER_NS or executable tmpfs access. O_NOATIME belongs to the
     * checked guest inode, not this root-owned carrier. */
    int fd=open("/dev/null",O_CLOEXEC|(f->flags&~(O_CREAT|O_EXCL|O_TRUNC|O_NOATIME)));
    return fd<0 ? -errno : fd;
}
int md_proc_file_access(const struct md_proc_file *f, enum md_proc_file_operation operation) {
    if (!f || (f->flags&O_PATH)) return -EBADF;
    if (operation==MD_PROC_FILE_COMMAND) return f->control && f->control->command ? 0 : -EINVAL;
    if (operation==MD_PROC_FILE_GETDENTS) return md_proc_file_directory(f->kind) || f->control_directory ? 0 : -ENOTDIR;
    if ((md_proc_file_directory(f->kind) || f->control_directory) && operation!=MD_PROC_FILE_SEEK) return -EISDIR;
    if (md_proc_file_namespace(f->kind)) return operation==MD_PROC_FILE_SEEK ? -ESPIPE : -EINVAL;
    if (operation==MD_PROC_FILE_SEEK && f->control && f->control->nonseekable) return -ESPIPE;
    int mode=f->flags&O_ACCMODE;
    return (operation==MD_PROC_FILE_READ && mode==O_WRONLY)
        || (operation==MD_PROC_FILE_WRITE && mode==O_RDONLY) ? -EBADF : 0;
}
static size_t slice(void *out, size_t capacity, uint64_t offset, const void *data, size_t size) {
    if (offset>=size) return 0;
    size-=offset;
    if (size>capacity) size=capacity;
    if (size) memcpy(out,(const char *)data+offset,size);
    return size;
}
static pid_t process_number(struct md_pid_namespace *view, long native) {
    if (native<=0 || !view) return (pid_t)native;
    struct md_pid_identity *id=md_pid_namespace_find(view,(pid_t)native);
    pid_t number=md_pid_identity_number(id,view);
    md_pid_identity_release(id); return number;
}
static void process_status_line(struct md_proc_file *f, FILE *out, const char *line) {
    if (f->process_state) {
        if (!strncmp(line,"TracerPid:",10)) {
            fprintf(out,"TracerPid:\t%d\n",process_number(f->pid_view,f->tracer)); return;
        }
        if (!strncmp(line,"NoNewPrivs:",11)) {
            fprintf(out,"NoNewPrivs:\t%d\n",!!f->process_identity.no_new_privs); return;
        }
        int group=!strncmp(line,"Gid:",4);
        if (group || !strncmp(line,"Uid:",4)) {
            const struct md_identity_ids *ids=group ? &f->process_identity.gid : &f->process_identity.uid;
            fprintf(out,"%s\t%u\t%u\t%u\t%u\n",group ? "Gid:" : "Uid:",
                md_identity_visible_id(&f->opener,group,ids->real),
                md_identity_visible_id(&f->opener,group,ids->effective),
                md_identity_visible_id(&f->opener,group,ids->saved),
                md_identity_visible_id(&f->opener,group,ids->fs)); return;
        }
        if (!strncmp(line,"Groups:",7)) {
            fputs("Groups:\t",out);
            const uint32_t *groups=md_identity_group_data(&f->process_identity);
            for (unsigned i=0;i<md_identity_group_count(&f->process_identity);++i)
                fprintf(out,"%u ",md_identity_visible_id(&f->opener,1,groups[i]));
            fputc('\n',out); return;
        }
        static const char *const names[]={"CapInh:","CapPrm:","CapEff:","CapBnd:","CapAmb:"};
        const struct md_capabilities *caps=&f->process_identity.caps;
        const uint64_t values[]={caps->inheritable,caps->permitted,caps->effective,caps->bounding,caps->ambient};
        for (unsigned i=0;i<5;++i) if (!strncmp(line,names[i],7)) {
            fprintf(out,"%s\t%016llx\n",names[i],(unsigned long long)values[i]); return;
        }
    }
    static const char *const ids[]={"Tgid:","Pid:","PPid:","TracerPid:"};
    static const char *const lists[]={"NStgid:","NSpid:","NSpgid:","NSsid:"};
    for (unsigned i=0;i<sizeof(ids)/sizeof(*ids);++i) {
        size_t n=strlen(ids[i]);
        if (!strncmp(line,ids[i],n)) {
            fprintf(out,"%s\t%d\n",ids[i],process_number(f->pid_view,strtol(line+n,NULL,10))); return;
        }
    }
    for (unsigned i=0;i<sizeof(lists)/sizeof(*lists);++i) {
        size_t n=strlen(lists[i]);
        if (strncmp(line,lists[i],n)) continue;
        pid_t native=(pid_t)strtol(line+n,NULL,10);
        /* A proc mount belongs to the observer's namespace, not the selected
         * task's. Keep the target chain even for the implicit root proc view. */
        struct md_pid_namespace *views[33], *view=f->process_pid_namespace;
        unsigned count=0;
        while (view && view!=f->pid_view && count<32) {
            views[count++]=view; view=md_pid_namespace_parent(view);
        }
        fprintf(out,"%s\t%d",lists[i],process_number(f->pid_view,native));
        while (count) fprintf(out,"\t%d",process_number(views[--count],native));
        fputc('\n',out); return;
    }
    fputs(line,out);
}
static int process_content(struct md_proc_file *f) {
    char path[64];
    snprintf(path,sizeof(path),"/proc/%d/%s",f->node.process,md_proc_file_process_name(f->kind));
    int fd=open(path,O_RDONLY|O_CLOEXEC);
    if (fd<0) return -errno;
    struct stat st;
    int error=fstat(fd,&st) ? -errno : st.st_dev!=f->node.device || st.st_ino!=f->node.inode ? -ESRCH : 0;
    char source[16384]; size_t size=0;
    while (!error) {
        ssize_t n=read(fd,source+size,sizeof(source)-1-size);
        if (n<0) { if (errno==EINTR) continue; error=-errno; break; }
        if (!n) break;
        size+=(size_t)n;
        if (size==sizeof(source)-1) error=-EOVERFLOW;
    }
    close(fd);
    if (error) return error;
    source[size]=0;
    char *data=NULL; size_t length=0;
    FILE *out=open_memstream(&data,&length);
    if (!out) return -errno;
    if (f->kind==MD_PROC_FILE_LIMITS) {
        char *line=source;
        while (*line) {
            char *end=strchr(line,'\n');
            if (!end) { error=-EIO; break; }
            int nproc=!strncmp(line,"Max processes ",14), nofile=!strncmp(line,"Max open files ",15);
            if (f->limits_valid && (nproc || nofile)) {
                struct rlimit value=nproc ? f->limits.nproc : f->limits.nofile;
                char soft[32],hard[32];
                if (value.rlim_cur==RLIM_INFINITY) strcpy(soft,"unlimited");
                else snprintf(soft,sizeof(soft),"%llu",(unsigned long long)value.rlim_cur);
                if (value.rlim_max==RLIM_INFINITY) strcpy(hard,"unlimited");
                else snprintf(hard,sizeof(hard),"%llu",(unsigned long long)value.rlim_max);
                fprintf(out,"%-25s %-20s %-20s %-10s\n",nproc ? "Max processes" : "Max open files",
                    soft,hard,nproc ? "processes" : "files");
            } else fwrite(line,1,end+1-line,out);
            line=end+1;
        }
    } else if (f->kind==MD_PROC_FILE_STATUS) {
        int have_namespace_fields=strstr(source,"\nNSpid:")!=NULL;
        char *tgid_line=strstr(source,"\nTgid:");
        pid_t tgid=tgid_line ? (pid_t)strtol(tgid_line+6,NULL,10) : f->node.process;
        char *line=source;
        while (*line) {
            char *end=strchr(line,'\n');
            if (!end) { error=-EIO; break; }
            char saved=end[1]; end[1]=0; process_status_line(f,out,line); end[1]=saved; line=end+1;
        }
        /* CONFIG_PID_NS is optional on the host. The mounted guest proc view
         * still publishes its own namespace identity fields. */
        if (!have_namespace_fields) {
            static const char *const names[]={"NStgid:","NSpid:","NSpgid:","NSsid:"};
            pid_t ids[]={tgid,f->node.process,getpgid(tgid),getsid(tgid)};
            for (unsigned i=0;i<4;++i) {
                char value[64]; snprintf(value,sizeof(value),"%s\t%d\n",names[i],ids[i]<0 ? 0 : ids[i]);
                process_status_line(f,out,value);
            }
        }
    } else {
        /* comm may contain spaces and parentheses. Only the final ')' bounds
         * it; the remaining fields have the kernel's stable numeric grammar. */
        char *start=strchr(source,'('), *end=strrchr(source,')');
        if (!start || !end || end<start || end[1]!=' ') error=-EIO;
        else {
            fprintf(out,"%d %.*s",process_number(f->pid_view,f->node.process),(int)(end-start+1),start);
            char *save=NULL; unsigned field=3;
            for (char *value=strtok_r(end+2," \n",&save);value;value=strtok_r(NULL," \n",&save),++field) {
                if (field==4 || field==5 || field==6 || field==8)
                    fprintf(out," %d",process_number(f->pid_view,strtol(value,NULL,10)));
                else fprintf(out," %s",value);
            }
            fputc('\n',out);
        }
    }
    if (ferror(out)) error=-EIO;
    if (fclose(out) && !error) error=-errno;
    if (error) free(data); else { f->content=data; f->content_size=length; }
    return error;
}
long md_proc_file_read_at(struct md_proc_file *f, const struct md_uts_namespace *uts, const struct md_cgroup_namespace *cgroup,
        void *out, size_t capacity, int64_t offset) {
    if (!f || (f->flags&O_PATH) || (f->flags&O_ACCMODE)==O_WRONLY) return -EBADF;
    if (offset<0) return -EINVAL;
    if (md_proc_file_directory(f->kind)) return -EISDIR;
    if (md_proc_file_namespace(f->kind)) return -EINVAL;
    if (md_proc_file_cgroup_kind(f->kind)) {
        char text[8192];
        long size=f->kind==MD_PROC_FILE_CGROUP ? md_cgroup_member_text(f->cgroup,cgroup,text,sizeof(text))
            : md_cgroup_controllers_text(f->cgroup,text,sizeof(text));
        return size<0 ? size : (long)slice(out,capacity,(uint64_t)offset,text,(size_t)size);
    }
    if (md_proc_file_process(f->kind)) {
        if (!capacity) return 0;
        int error=f->content ? 0 : process_content(f);
        return error ? error : (long)slice(out,capacity,(uint64_t)offset,f->content,f->content_size);
    }
    if (md_proc_file_sysctl(f->kind)) return md_proc_sysctl_read(uts,f->kind,out,capacity,offset);
    if (f->kind==MD_PROC_FILE_SETGROUPS) {
        const char *s=md_user_namespace_groups_allowed(f->target) ? "allow\n" : "deny\n";
        return (long)slice(out,capacity,(uint64_t)offset,s,strlen(s));
    }
    struct md_id_extent ranges[MD_USER_MAP_EXTENTS];
    int group=f->kind==MD_PROC_FILE_GID_MAP;
    int count=md_user_namespace_extents(f->target,group,ranges,MD_USER_MAP_EXTENTS);
    if (count<0) return count;
    const struct md_user_namespace *observer=f->opener.user_namespace;
    const struct md_user_namespace *parent=md_user_namespace_parent(f->target);
    if (observer==f->target && f->target) observer=parent;
    size_t copied=0;
    for (int i=0;i<count && copied<capacity;++i) {
        uint32_t lower=md_user_namespace_into(parent,group,ranges[i].outside);
        lower=md_user_namespace_from(observer,group,lower);
        char line[34];
        int size=snprintf(line,sizeof(line),"%10u %10u %10u\n",ranges[i].inside,lower,ranges[i].count);
        if (size<0 || (size_t)size>=sizeof(line)) return -EOVERFLOW;
        if (offset>=size) { offset-=size; continue; }
        copied+=slice((char *)out+copied,capacity-copied,(uint64_t)offset,line,(size_t)size);
        offset=0;
    }
    return (long)copied;
}
static int space(unsigned char c) {
    return c==' ' || (c>='\t' && c<='\r');
}
static int number(const char **text, const char *end, uint32_t *out) {
    const char *p=*text;
    while (p<end && space(*p)) ++p;
    if (p==end || *p<'0' || *p>'9') return -EINVAL;
    uint64_t value=0;
    do {
        value=value*10+(unsigned)(*p++-'0');
        if (value>UINT32_MAX) return -EINVAL;
    } while (p<end && *p>='0' && *p<='9');
    *text=p; *out=(uint32_t)value; return 0;
}
static int parse(const char *text, size_t size, struct md_id_extent *ranges) {
    const char *end=memchr(text,0,size);
    if (!end) end=text+size;
    unsigned count=0;
    while (text<end) {
        if (count==MD_USER_MAP_EXTENTS) return -EINVAL;
        const char *line=memchr(text,'\n',(size_t)(end-text));
        if (!line) line=end;
        struct md_id_extent *r=ranges+count;
        if (number(&text,line,&r->inside) || text==line || !space(*text)
                || number(&text,line,&r->outside) || text==line || !space(*text)
                || number(&text,line,&r->count)) return -EINVAL;
        while (text<line && space(*text)) ++text;
        if (text!=line) return -EINVAL;
        text=line<end ? line+1 : end; ++count;
    }
    return count ? (int)count : -EINVAL;
}
static struct md_user_authority authority(const struct md_identity *id) {
    return (struct md_user_authority){id->user_namespace,id->uid.effective,id->gid.effective,id->caps.effective};
}
long md_proc_file_write_at(struct md_proc_file *f, const struct md_identity *writer, struct md_uts_namespace *uts,
        const void *data, size_t size, int64_t offset, size_t page_size) {
    if (!f || !writer || (f->flags&O_PATH) || (f->flags&O_ACCMODE)==O_RDONLY) return -EBADF;
    if (md_fs_anchor_policy(f->placement)&MD_MOUNT_READONLY) return -EROFS;
    if (md_proc_file_namespace(f->kind)) return -EINVAL;
    if (md_proc_file_sysctl(f->kind)) return md_proc_sysctl_write(uts,f->kind,writer,data,size,&offset);
    if (offset || size>=(f->kind==MD_PROC_FILE_SETGROUPS ? 8 : page_size)) return -EINVAL;
    if (f->kind==MD_PROC_FILE_SETGROUPS) {
        const char *p=data; int allowed;
        if (size>=5 && !memcmp(p,"allow",5)) { p+=5; allowed=1; }
        else if (size>=4 && !memcmp(p,"deny",4)) { p+=4; allowed=0; }
        else return -EINVAL;
        const char *end=(const char *)data+size;
        while (p<end && space(*p)) ++p;
        if (p<end && *p) return -EINVAL;
        int error=md_user_namespace_setgroups(f->target,allowed);
        return error ? error : (long)size;
    }
    struct md_user_authority opened=authority(&f->opener), current=authority(writer);
    int error=md_user_namespace_map_admit(f->target,f->kind==MD_PROC_FILE_GID_MAP,&opened);
    if (error) return error;
    struct md_id_extent ranges[MD_USER_MAP_EXTENTS];
    int count=parse(data,size,ranges);
    if (count<0) return count;
    error=md_user_namespace_map(f->target,f->kind==MD_PROC_FILE_GID_MAP,&opened,&current,ranges,(unsigned)count);
    return error ? error : (long)size;
}
int md_proc_file_io_begin(struct md_proc_file_io *io, struct md_proc_file *file,
        enum md_proc_file_operation operation, int positional, int64_t offset, int whence,
        size_t length, struct md_resource_wakeup *wake) {
    if (!io || !file || operation<MD_PROC_FILE_READ || operation>MD_PROC_FILE_COMMAND) return -EINVAL;
    if (io->file || io->turn.queue) return -EBUSY;
    int ready=md_resource_order_begin(&file->operations,&io->turn,wake);
    if (ready<0) return ready;
    io->file=md_proc_file_retain(file); io->operation=operation;
    io->positional=!!positional; io->offset=offset; io->whence=whence; io->length=length;
    return ready;
}
static int directory_append(struct md_proc_file *f,const char *name,unsigned char type,uint64_t inode) {
    size_t length=strlen(name);
    size_t record=md_directory_record_size(length);
    if (!record || f->directory_size>65536-record) return -EOVERFLOW;
    if (f->directory_size+record>f->directory_capacity) {
        size_t capacity=f->directory_capacity ? f->directory_capacity*2 : 4096;
        while (capacity<f->directory_size+record) capacity*=2;
        void *data=realloc(f->directory,capacity);
        if (!data) return -ENOMEM;
        f->directory=data; f->directory_capacity=capacity;
    }
    md_directory_record_write((char *)f->directory+f->directory_size,record,
        name,length,inode,(int64_t)(f->directory_size+record),type);
    f->directory_size+=record; return 0;
}
static int process_directory(struct md_proc_file *f,const char *path) {
    if (f->directory) return 0;
    int fd=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    if (fd<0) return -errno;
    struct stat st;
    int r=fstat(fd,&st) ? -errno : st.st_dev!=f->node.device || st.st_ino!=f->node.inode ? -ENOENT : 0;
    static const char *const names[]={"uid_map","gid_map","setgroups"};
    char data[4096];
    while (!r) {
        ssize_t size=syscall(SYS_getdents64,fd,data,sizeof(data));
        if (size<0) { r=-errno; break; }
        if (!size) break;
        for (size_t at=0; !r && at<(size_t)size;) {
            struct md_directory_entry entry;
            if (!md_directory_record_read(data+at,(size_t)size-at,&entry)) { r=-EIO; break; }
            const char *name=data+at+offsetof(struct md_directory_entry,name);
            int mapped=0;
            for (unsigned i=0;i<3;++i) mapped|=!strcmp(name,names[i]);
            if (!mapped) r=directory_append(f,name,entry.type,entry.inode);
            at+=entry.size;
        }
    }
    if (!r && fstatat(fd,"status",&st,0)) r=-errno;
    for (unsigned i=0; !r && i<3; ++i) r=directory_append(f,names[i],DT_REG,
        ((uint64_t)st.st_ino<<2)|(i+1)|UINT64_C(0x8000000000000000));
    close(fd);
    if (r) { free(f->directory); f->directory=NULL; f->directory_size=f->directory_capacity=0; }
    return r;
}
long md_proc_file_namespace_entries(uint64_t inode,int64_t *position,void *bytes,size_t capacity) {
    static const char *const names[]={".","..","user","mnt","uts","ipc","pid","pid_for_children","net","cgroup"};
    if (*position<0) return -EINVAL;
    size_t size=0;
    for (int64_t i=*position;i<(int64_t)(sizeof(names)/sizeof(*names));++i) {
        size_t record=md_directory_record_write((char *)bytes+size,capacity-size,names[i],strlen(names[i]),
            i ? (inode<<4)|(uint64_t)i : inode,i+1,i<2 ? DT_DIR : DT_LNK);
        if (!record) return size ? (long)size : -EINVAL;
        size+=record; *position=i+1;
    }
    return (long)size;
}
static long directory_entries(struct md_proc_file_io *io, void *bytes, size_t capacity) {
    struct md_proc_file *f=io->file;
    char path[64]; snprintf(path,sizeof(path),"/proc/%d%s",f->node.process,
        f->kind==MD_PROC_FILE_NS_DIRECTORY ? "/ns" : "");
    struct stat st;
    if (stat(path,&st)) return -errno;
    if (st.st_dev!=f->node.device || st.st_ino!=f->node.inode) return -ENOENT;
    if (capacity>io->length) capacity=io->length;
    size_t size=0;
    io->next_position=io->offset;
    if (f->kind==MD_PROC_FILE_PROCESS_DIRECTORY) {
        int r=process_directory(f,path);
        if (r) return r;
        size_t at=0;
        while (at<f->directory_size && at<(uint64_t)io->offset) {
            const struct md_directory_entry *e=(const void *)((const char *)f->directory+at);
            at+=e->size;
        }
        if (at<f->directory_size && at!=(uint64_t)io->offset) return -EINVAL;
        while (at<f->directory_size) {
            const struct md_directory_entry *e=(const void *)((const char *)f->directory+at);
            if (e->size>capacity-size) return size ? (long)size : -EINVAL;
            memcpy((char *)bytes+size,e,e->size);
            size+=e->size; at+=e->size; io->next_position=(int64_t)at;
        }
        return (long)size;
    }
    return md_proc_file_namespace_entries(f->node.inode,&io->next_position,bytes,capacity);
}
long md_proc_file_io_execute(struct md_proc_file_io *io, const struct md_identity *writer, struct md_uts_namespace *uts,
        void *bytes, size_t capacity, size_t page_size) {
    if (!io || !io->file || (io->executed && !io->pending)) return -EINVAL;
    /* A reusable transport buffer is not the application's requested count.
     * Preserve that bound on every continuation, including directory pages. */
    if ((io->operation==MD_PROC_FILE_READ || io->operation==MD_PROC_FILE_WRITE
            || io->operation==MD_PROC_FILE_GETDENTS) && capacity>io->length)
        capacity=io->length;
    if (io->pending) {
        if (capacity>MD_CONTROL_IO_MAX) capacity=MD_CONTROL_IO_MAX;
        io->result=io->pending->resume(io->pending,bytes,capacity);
        if (io->result==-EINPROGRESS) return io->result;
        io->next_position=io->pending->position;
        int position_valid=io->pending->position_valid;
        io->pending->dispose(io->pending); io->pending=NULL;
        if (io->operation==MD_PROC_FILE_WRITE && io->result>0 && !io->positional)
            io->file->position=position_valid ? io->next_position : io->offset+io->result;
        if (io->operation==MD_PROC_FILE_SEEK && io->result>=0) io->file->position=io->result;
        return io->result;
    }
    int error=md_resource_order_state(&io->turn)==MD_RESOURCE_ORDER_RUNNING
        ? 0 : md_resource_order_start(&io->turn);
    if (error) return error;
    io->executed=1;
    struct md_proc_file *f=io->file;
    io->actor.status_flags=atomic_load_explicit(&f->flags,memory_order_relaxed);
    error=md_proc_file_access(f,io->operation);
    if (error) return io->result=error;
    if (io->positional && f->control && f->control->nonseekable) return io->result=-ESPIPE;
    if (io->operation==MD_PROC_FILE_COMMAND) {
        io->actor.wakeup=io->turn.wakeup;
        io->result=f->control->command(f->control_context,&io->actor,(unsigned)io->whence,io->offset,&io->pending);
        if (io->pending) {
            if (io->result==-EINPROGRESS) return io->result;
            io->pending->dispose(io->pending); io->pending=NULL;
        } else if (io->result==-EINPROGRESS) io->result=-EPROTO;
        return io->result;
    }
    if (io->operation==MD_PROC_FILE_SEEK) {
        int64_t offset=io->offset;
        if (f->flags&O_PATH) return io->result=-EBADF;
        if (f->control && f->control->seek) {
            io->actor.wakeup=io->turn.wakeup;
            io->result=f->control->seek(f->control_context,&io->actor,offset,io->whence,&io->pending);
            if (io->pending) {
                if (io->result==-EINPROGRESS) return io->result;
                io->pending->dispose(io->pending); io->pending=NULL;
            } else if (io->result==-EINPROGRESS) io->result=-EPROTO;
            if (io->result>=0) f->position=io->result;
            if (io->result!=-ENOSYS) return io->result;
        }
        if (io->whence==SEEK_END && f->control && f->control->descriptor) {
            struct stat st;
            int error=f->control->stat(f->control_context,&st);
            if (error) return io->result=error;
            if (__builtin_add_overflow(offset,st.st_size,&offset) || offset<0) return io->result=-EINVAL;
            f->position=offset; return io->result=offset;
        }
        if ((io->whence!=SEEK_SET && io->whence!=SEEK_CUR)
                || (io->whence==SEEK_CUR && __builtin_add_overflow(offset,f->position,&offset))
                || offset<0) return io->result=-EINVAL;
        if (!offset && md_proc_file_process(f->kind)) { free(f->content); f->content=NULL; f->content_size=0; }
        f->position=offset; return io->result=offset;
    }
    if (!io->positional) io->offset=f->position;
    if (io->offset<0) return io->result=-EINVAL;
    if (f->control && f->control->io) {
        size_t length=io->length<capacity ? io->length : capacity;
        if (length>MD_CONTROL_IO_MAX) length=MD_CONTROL_IO_MAX;
        if (io->operation==MD_PROC_FILE_READ && f->control->read_size) {
            size_t page=f->control->read_size(f->control_context);
            if (page) {
                if (length>page) length=page;
                io->read_page=length;
            }
        }
        if (length && !bytes) return io->result=-EFAULT;
        io->actor.wakeup=io->turn.wakeup;
        io->result=f->control->io(f->control_context,&io->actor,
            io->operation==MD_PROC_FILE_WRITE ? MD_CONTROL_WRITE
                : io->operation==MD_PROC_FILE_GETDENTS ? MD_CONTROL_GETDENTS : MD_CONTROL_READ,
            bytes,length,io->offset,&io->pending);
        if (io->pending) {
            if (io->result==-EINPROGRESS) return io->result;
            io->next_position=io->pending->position;
            if (io->operation==MD_PROC_FILE_WRITE && io->result>0 && !io->positional
                    && io->pending->position_valid) io->offset=io->next_position-io->result;
            io->pending->dispose(io->pending); io->pending=NULL;
        } else if (io->result==-EINPROGRESS) io->result=-EPROTO;
        if (io->operation==MD_PROC_FILE_WRITE && io->result>0 && !io->positional)
            f->position=io->offset+io->result;
        return io->result;
    }
    if (io->operation==MD_PROC_FILE_GETDENTS) {
        if (capacity && !bytes) return io->result=-EFAULT;
        return io->result=directory_entries(io,bytes,capacity);
    }
    if (io->operation==MD_PROC_FILE_WRITE) {
        if (f->control && !md_proc_file_sysctl(f->kind)) {
            if (io->length>capacity || io->length>MD_CONTROL_IO_MAX || (io->length && !bytes)) return io->result=-EINVAL;
            io->actor.wakeup=io->turn.wakeup;
            io->result=f->control->write(f->control_context,&io->actor,bytes,io->length,io->offset);
            if (io->result>0 && !io->positional) f->position=io->offset+io->result;
            return io->result;
        }
        /* Reject impossible proc writes before copying or allocating input. */
        size_t limit=f->kind==MD_PROC_FILE_SETGROUPS ? 8 : page_size;
        if ((f->flags&O_PATH) || (f->flags&O_ACCMODE)==O_RDONLY) return io->result=-EBADF;
        int sysctl=md_proc_file_sysctl(f->kind);
        if (sysctl && f->control && (md_proc_file_control_access(f,writer,W_OK)==-EROFS))
            return io->result=-EROFS;
        if (!sysctl && (io->offset || io->length>=limit)) return io->result=-EINVAL;
        size_t required=sysctl && io->length>64 ? 64 : io->length;
        if (required>capacity || (required && !bytes)) return io->result=-EFAULT;
        int64_t next=io->offset;
        io->result=sysctl ? md_proc_sysctl_write(uts,f->kind,writer,bytes,io->length,&next)
            : md_proc_file_write_at(f,writer,uts,bytes,io->length,io->offset,page_size);
        if (io->result>0 && !io->positional) f->position=sysctl ? next : io->offset+io->result;
    } else {
        size_t length=io->length<capacity ? io->length : capacity;
        if (length && !bytes) return io->result=-EFAULT;
        io->actor.wakeup=io->turn.wakeup;
        io->result=f->control ? f->control->read(f->control_context,&io->actor,bytes,length,io->offset)
            : md_proc_file_read_at(f,uts,io->actor.resources ? io->actor.resources->cgroup_namespace : NULL,bytes,length,io->offset);
    }
    return io->result;
}
int md_proc_file_io_offer(struct md_proc_file_io *io) {
    if (!io || !io->file || !io->executed || io->pending || io->offered
            || md_resource_order_state(&io->turn)!=MD_RESOURCE_ORDER_RUNNING) return -EINVAL;
    io->offered=1; return 0;
}
int md_proc_file_io_more(const struct md_proc_file_io *io) {
    return io && io->file && io->operation==MD_PROC_FILE_READ && io->executed && !io->pending
        && io->read_page && io->result==(long)io->read_page && io->read_page<io->length;
}
static int read_acknowledge(struct md_proc_file_io *io,long copied) {
    if (copied>io->result || (copied>0 && !io->offered)) return -EINVAL;
    if (copied>0) {
        if (io->offset>INT64_MAX-copied) return -EOVERFLOW;
        if (!io->positional) io->file->position=io->offset+copied;
    }
    return 0;
}
int md_proc_file_io_advance(struct md_proc_file_io *io,long copied) {
    if (!md_proc_file_io_more(io) || copied!=io->result || !io->offered) return -EINVAL;
    int error=read_acknowledge(io,copied);
    if (error) return error;
    io->offset+=copied; io->length-=(size_t)copied; io->transferred+=(size_t)copied;
    io->result=0; io->executed=io->offered=0; io->read_page=0;
    return 0;
}
static void retired_io(struct md_control_pending *pending,long result) {
    struct md_proc_file *file=pending->retirement_context;
    if (pending->retirement_advance && result>0)
        file->position=pending->position_valid ? pending->position : pending->retirement_offset+result;
    md_resource_order_complete(&pending->retirement);
    md_proc_file_release(file);
}
int md_proc_file_io_finish(struct md_proc_file_io *io, long copied) {
    if (!io || !io->file || (!io->executed && !io->transferred)) return -EINVAL;
    if (io->pending && io->pending->abandon) {
        struct md_control_pending *pending=io->pending;
        int error=md_resource_order_move(&io->turn,&pending->retirement);
        if (error) return error;
        pending->retirement_context=io->file;
        pending->retirement_offset=io->offset;
        pending->retirement_advance=io->operation==MD_PROC_FILE_WRITE && !io->positional;
        pending->retired=retired_io;
        *io=(struct md_proc_file_io){0};
        pending->abandon(pending); return 0;
    }
    if (io->operation==MD_PROC_FILE_READ && io->result>=0) {
        int error=read_acknowledge(io,copied);
        if (error) return error;
    }
    if (io->operation==MD_PROC_FILE_GETDENTS && io->result>=0) {
        if (copied>0 && (!io->offered || copied!=io->result)) return -EINVAL;
        if (copied>0) io->file->position=io->next_position;
    }
    if ((io->operation==MD_PROC_FILE_READ || io->operation==MD_PROC_FILE_GETDENTS)
            && (io->result>=0 || io->transferred) && io->file->control && io->file->control->read_commit)
        io->file->control->read_commit(io->file->control_context,
            io->transferred+(copied>0 ? (size_t)copied : 0));
    if (io->pending) { io->pending->dispose(io->pending); io->pending=NULL; }
    int error=md_resource_order_complete(&io->turn);
    if (io->turn.queue) return error;
    md_proc_file_release(io->file); *io=(struct md_proc_file_io){0}; return error;
}
int md_proc_file_io_cancel(struct md_proc_file_io *io) {
    if (!io || !io->file) return 0;
    int error=md_resource_order_cancel(&io->turn);
    if (error==1) return md_proc_file_io_finish(io,io->offered && io->result>0 ? io->result : 0);
    if (io->turn.queue) return error;
    md_proc_file_release(io->file); *io=(struct md_proc_file_io){0}; return error;
}
