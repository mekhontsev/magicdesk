#define _GNU_SOURCE
#include "fs_native.h"
#include "fs_mount_internal.h"
#include "inode_internal.h"
#include "proc_paths.h"
#include "procfs_view.h"
#include <linux/magic.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/openat2.h>
#include <linux/stat.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/vfs.h>
#include <unistd.h>

void md_fs_directory_read(const struct md_fs_request *q, struct md_fs_result *out,
        const struct md_fs_output *output, md_fs_directory_reader reader, void *context) {
    if (!output || !output->data || !output->capacity) { out->error=-EINVAL; return; }
    int fd=q->directory[0];
    off_t before=lseek(fd,0,SEEK_CUR);
    if (before<0) { out->error=-errno; return; }
    size_t count=q->capacity<output->capacity ? q->capacity : output->capacity;
    ssize_t n=reader ? reader(context,fd,output->data,count)
        : syscall(SYS_getdents64,fd,output->data,count);
    if (n<0) { out->error=reader ? (int)n : -errno; return; }
    int error=0;
    if (output->directory_position) {
        off_t next=lseek(fd,0,SEEK_CUR);
        if (next<0) error=-errno; else *output->directory_position=next;
    } else if (output->deliver) error=output->deliver(output->context,output->data,(size_t)n);
    if (error || output->directory_position)
        if (lseek(fd,before,SEEK_SET)<0) error=-errno;
    out->error=error;
    if (!error) out->size=(size_t)n;
}

static ssize_t native_directory(void *context,int fd,void *data,size_t size) {
    struct statfs type;
    if (fstatfs(fd,&type)) return -errno;
    if (type.f_type==PROC_SUPER_MAGIC) {
        /* An implicit proc directory retains the native PID view, but its
         * entries still belong to the selected guest network namespace. */
        const struct md_view_mount proc={.backing=MD_MOUNT_PROC};
        return md_procfs_getdents(context,&proc,fd,data,size);
    }
    ssize_t n=syscall(SYS_getdents64,fd,data,size);
    return n<0 ? -errno : n;
}
void md_fs_native_descriptor(struct md_filesystem *fs,const struct md_fs_request *q, struct md_fs_result *out,
        const struct md_fs_output *output) {
    if (q->operation==MD_FS_FSTAT) {
        struct stat st;
        out->error=fstat(q->directory[0],&st) ? -errno : 0;
        if (!out->error) md_fs_stat_info(&st,&out->info);
        if (!out->error && (q->mode&MD_FS_STAT_MOUNT)) {
            struct statx native;
            if (syscall(SYS_statx,q->directory[0],"",AT_EMPTY_PATH,STATX_MNT_ID,&native)) out->error=-errno;
            else if ((native.stx_mask&STATX_MNT_ID) && native.stx_mnt_id) {
                uint64_t mount=0;
                out->error=md_fs_native_mount(fs,native.stx_mnt_id,&mount);
                if (!out->error) md_fs_info_mount(&out->info,mount,!!(native.stx_attributes&STATX_ATTR_MOUNT_ROOT));
            }
        }
    } else if (q->operation==MD_FS_STATFS && (!q->path[0] || !*q->path[0])) {
        out->error=0;
        md_fs_statfs_result(q->directory[0],0,NULL,out,output);
    } else if (q->operation==MD_FS_GETDENTS) md_fs_directory_read(q,out,output,native_directory,fs);
    else if (q->operation==MD_FS_READLINK) {
        struct statfs type;
        if (fstatfs(q->directory[0],&type)) { out->error=-errno; return; }
        if (type.f_type!=PROC_SUPER_MAGIC) return;
        if (!output || !output->data || !output->capacity) { out->error=-EINVAL; return; }
        /* A pinned proc link has the same target semantics with or without
         * an explicit guest proc mount. Never expose its storage backing. */
        const struct md_view_mount proc={.backing=MD_MOUNT_PROC};
        ssize_t n=md_procfs_readlink(fs,&proc,q->directory[0],output->data,output->capacity);
        out->error=n<0 ? (int)n : 0;
        if (n>=0) out->size=(size_t)n;
    }
}

static int task_relative(struct md_filesystem *fs, const char *path,
        const struct md_fs_request *q, struct md_fd_order *order, struct md_fs_anchor **origin) {
    /* Boundary suffixes have not been walked by the guest resolver. Never
     * reinterpret parent traversal or a self alias in the worker's context. */
    for (const char *p=path; *p;) {
        while (*p=='/') ++p;
        const char *end=strchr(p,'/');
        size_t n=end ? (size_t)(end-p) : strlen(p);
        if ((n==2 && !memcmp(p,"..",2)) || (n==4 && !memcmp(p,"self",4))
                || (n==11 && !memcmp(p,"thread-self",11))) return 1;
        p+=n;
    }
    struct md_proc_path ref=md_proc_path_for(path,fs->actor->tid,fs->actor->tgid);
    if (!ref.process) return ref.kind!=MD_PROC_NONE;
    struct md_task_snapshot task;
    int error=md_task_registry_probe(fs->tasks,ref.process,0,&task);
    int relative=error!=-ESRCH;
    /* Native service owners are registered for authenticated worker requests,
     * not as guest processes. They have no guest file table or typed carriers;
     * proc inspection retains kernel access checks, as for external owners. */
    if (!error && !task.files) {
        /* Looking at ourselves would bypass the kernel's remote dumpability
         * check. Protected owners retain observation from the caller. */
        relative=task.tgid==getpid() && prctl(PR_GET_DUMPABLE)!=1;
    }
    enum md_proc_kind kind=ref.kind==MD_PROC_FOREIGN ? ref.foreign_kind : ref.kind;
    if (!error && task.files && kind==MD_PROC_FD && ref.tail && !*ref.tail
            && (q->operation==MD_FS_STAT || q->operation==MD_FS_READLINK)
            && (task.tid==fs->actor->tid || (!md_domain_restricted(fs->actor->fs)
                && md_identity_may_inspect(&fs->actor->identity,&task.identity,1)))) {
        /* Capture metadata and the kernel target under the same table turn.
         * Never wait in the worker: an occupied table uses task-affine admission.
         * Typed proc/control carriers must still go through their provider. */
        if (md_fd_order_begin(task.files,order,NULL)==1 && !md_fd_order_start(order)) {
            struct md_fd_object *object=md_fd_origins_object(task.files,ref.descriptor,NULL);
            *origin=md_fs_anchor_retain(md_fs_anchor_from_object(object));
            relative=object && !*origin;
            md_fd_object_release(object);
        }
    }
    md_task_snapshot_release(&task);
    return relative;
}

static int object_link(struct md_filesystem *fs, const char *path) {
    struct md_proc_path ref=md_proc_path_for(path,fs->actor->tid,fs->actor->tgid);
    enum md_proc_kind kind=ref.kind==MD_PROC_FOREIGN ? ref.foreign_kind : ref.kind;
    return (kind==MD_PROC_FD || kind==MD_PROC_CWD || kind==MD_PROC_ROOT || kind==MD_PROC_EXE)
        && ref.tail && !*ref.tail;
}

static void native_link(struct md_filesystem *fs, const char *path, int fd, mode_t mode,
        struct md_fs_anchor *origin, struct md_fs_result *out, const struct md_fs_output *output) {
    if (!output || !output->data || !output->capacity) { out->error=-EINVAL; return; }
    char text[PATH_MAX];
    ssize_t n=readlinkat(fd,"",text,sizeof(text));
    if (n<0) { out->error=errno==ENOENT && !S_ISLNK(mode) ? -EINVAL : -errno; return; }
    if (n && text[0]=='/' && object_link(fs,path)) {
        /* A native process may hold an object also visible through this guest's
         * bind/store. Preserve that logical name, as the task-affine adapter
         * does; socket/pipe identities need no target open or namespace query. */
        int target=open(path,O_PATH|O_CLOEXEC);
        if (target<0) { out->error=-errno; return; }
        struct md_fs_request query={.operation=MD_FS_PATH,.mode=MD_FS_PATH_DISPLAY,
            .directory={target,-1},.origin={origin,NULL}};
        struct md_fs_output name={.data=output->data,.capacity=output->capacity};
        md_fs_view_execute(fs,&query,out,&name);
        close(target);
        if (!out->error) { if (out->size) --out->size; return; }
        if (out->error!=-EXDEV) return;
    }
    out->error=0;
    out->size=(size_t)n<output->capacity ? (size_t)n : output->capacity;
    memcpy(output->data,text,out->size);
}

void md_fs_native_boundary(struct md_filesystem *fs, const struct md_inode_boundary *boundary,
        const struct md_fs_request *q, struct md_fs_result *out, const struct md_fs_output *output) {
    if (output && output->native_open_plan) return;
    /* A mounted native PID view has an explicit namespace inode too. Only
     * private PID views need task-affine number translation after the walk. */
    int private_pid=boundary->proc_namespace
        && boundary->proc_namespace!=md_pid_namespace_inode(NULL);
    if (!fs->actor || private_pid || boundary->operand || !boundary->path
            || q->resolve) return;
    if (q->operation!=MD_FS_STAT && q->operation!=MD_FS_READLINK && q->operation!=MD_FS_OPEN) return;
    if (q->operation==MD_FS_OPEN && (q->flags&(O_ACCMODE|O_CREAT|O_TRUNC|__O_TMPFILE))) return;
    struct md_fd_order order={0};
    struct md_fs_anchor *origin=NULL;
    if (task_relative(fs,boundary->path,q,&order,&origin)) goto finished;
    /* Pin a non-symlink prefix in one kernel walk. In particular /proc/net and
     * /dev/fd must not resolve through this worker's self links. NO_SYMLINKS
     * still permits the final O_PATH|O_NOFOLLOW link for readlink/lstat. */
    struct open_how how={.flags=O_PATH|O_NOFOLLOW|O_CLOEXEC,.resolve=RESOLVE_NO_SYMLINKS};
    int fd=(int)syscall(SYS_openat2,AT_FDCWD,boundary->path,&how,sizeof(how));
    if (fd<0) {
        if (errno!=ELOOP && errno!=ENOSYS && errno!=EINVAL && errno!=EPERM) out->error=-errno;
        goto finished;
    }
    struct stat st;
    if (fstat(fd,&st)) { out->error=-errno; close(fd); goto finished; }
    if (q->operation==MD_FS_READLINK) {
        native_link(fs,boundary->path,fd,st.st_mode,origin,out,output);
    } else if (q->operation==MD_FS_STAT) {
        if (!S_ISLNK(st.st_mode) || (q->flags&AT_SYMLINK_NOFOLLOW)) {
            out->error=0; md_fs_stat_info(&st,&out->info);
        } else if (object_link(fs,boundary->path)) {
            int target=open(boundary->path,O_PATH|O_CLOEXEC);
            if (target<0) out->error=-errno;
            else {
                struct md_fs_request query={.operation=MD_FS_FSTAT,.directory={target,-1},.origin={origin,NULL}};
                md_fs_view_execute(fs,&query,out,output);
                if (out->error==-EXDEV) md_fs_native_descriptor(fs,&query,out,output);
                close(target);
            }
        }
    } else if (S_ISREG(st.st_mode) || S_ISDIR(st.st_mode)) {
        /* Devices, FIFOs and magic links can have task-affine open semantics.
         * They retain their provider; regular observations cannot block it. */
        int result=md_reopen_file(fd,q->flags|O_CLOEXEC,&out->open_completion);
        out->error=result<0 ? result : 0;
        if (result>=0) { out->fd=result; md_view_capture(fs,NULL,NULL,out,output); }
    }
    close(fd);
finished:
    /* Proc access is caller-affine: a protected task can inspect itself even
     * when this worker cannot. Retry under that task's unchanged credentials. */
    if (out->error==-EACCES || out->error==-EPERM) out->error=-EREMOTE;
    md_fs_anchor_release(origin);
    if (order.table) {
        int error=md_fd_order_cancel(&order);
        if (error==1) error=md_fd_order_complete(&order);
        if (error) out->error=error;
    }
}
