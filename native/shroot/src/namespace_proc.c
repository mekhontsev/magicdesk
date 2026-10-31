#define _GNU_SOURCE
#include "namespace_internal.h"
#include "proc_paths.h"
#include "proc_control.h"
#include "proc_file.h"
#include "fd_metadata.h"
#include "descriptor_calls.h"
#include "interception.h"
#include "fd_client.h"
#include "fs_buffer.h"
#include "adapter_buffer.h"
#include "raw.h"
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/inotify.h>
#include <linux/stat.h>
#include <linux/openat2.h>
#ifndef STATX_MNT_ID_UNIQUE
#define STATX_MNT_ID_UNIQUE 0x4000U
#endif

static long mount_table_open(const struct md_fs *fs,const struct md_proc_path *ref,
        const char *path,int flags) {
    if (*ref->tail) return -ENOTDIR;
    if (flags&O_DIRECTORY) return -ENOTDIR;
    if (!(flags&O_PATH) && ((flags&O_ACCMODE)!=O_RDONLY || (flags&O_TRUNC))) return -EACCES;
    if ((flags&(O_CREAT|O_EXCL))==(O_CREAT|O_EXCL)) return -EEXIST;
    enum md_proc_kind kind=ref->kind==MD_PROC_FOREIGN ? ref->foreign_kind : ref->kind;
    struct md_fs_request q={.operation=MD_FS_MOUNT_TABLE,.directory={-1,-1},
        .mode=kind==MD_PROC_MOUNTINFO,.flags=(unsigned)flags&(O_PATH|O_CLOEXEC)};
    if (ref->kind==MD_PROC_FOREIGN)
        q.capture[0]=(struct md_fs_task_capture){path,(flags&O_PATH)|O_CLOEXEC};
    struct md_fs_response response;
    long error=md_fs_call(fs->endpoint,5000,&q,&response);
    if (!error) error=response.result.error;
    if (error) return error;
    return md_namespace_open_result(&response.result,(unsigned)flags);
}
static long fdinfo_open(const struct md_fs *fs,const struct md_proc_path *ref,
        int flags,uint64_t proc_namespace) {
    if (*ref->tail) return -ENOTDIR;
    if (ref->kind==MD_PROC_FOREIGN) return -ENOTSUP;
    if (flags&O_DIRECTORY) return -ENOTDIR;
    if (!(flags&O_PATH) && ((flags&O_ACCMODE)!=O_RDONLY || (flags&O_TRUNC))) return -EACCES;
    if ((flags&(O_CREAT|O_EXCL))==(O_CREAT|O_EXCL)) return -EEXIST;
    char selected[96]="/proc/";
    md_decimal(selected+md_length(selected),(unsigned)ref->process);
    md_append(selected,sizeof(selected),"/fdinfo/");
    md_decimal(selected+md_length(selected),(unsigned)ref->descriptor);
    struct md_fs_request q={.operation=MD_FS_FDINFO,.directory={-1,ref->descriptor},
        .capture={{selected,O_RDONLY|O_CLOEXEC}},
        .flags=(unsigned)flags&(O_PATH|O_CLOEXEC),.offset=(int64_t)proc_namespace};
    struct md_fs_response out;
    long result=md_namespace_request(fs,&q,&out);
    return result<0 ? result : out.result.fd;
}

long md_namespace_memory_open(pid_t process,long nr,const unsigned long *a) {
    long admitted=md_guest_buffer_control(MD_BUFFER_MEMORY_OPEN,(unsigned)process,0);
    if (admitted<0) return admitted;
    long result=md_raw(nr,a[0],a[1],a[2],a[3],a[4],a[5]);
    long completed=md_guest_buffer_control(MD_BUFFER_MEMORY_OPEN_RESULT,(unsigned long)result,0);
    if (completed<0) {
        if (result>=0) RAW1(close,result);
        return completed;
    }
    return result;
}

long md_namespace_host_open_resolved(const struct md_fs *fs,int base,const char *path,
        const char *identity,const struct open_how *how,uint64_t proc_namespace) {
    /* Identity selects the virtual proc object; the original dirfd/path pair
     * retains the kernel's relative openat2 scope and symlink checks. */
    struct md_proc_path ref=md_proc_path(identity);
    enum md_proc_kind kind=ref.kind==MD_PROC_FOREIGN ? ref.foreign_kind : ref.kind;
    if (ref.ordinary_link && !*ref.tail
            && ((how->flags&O_NOFOLLOW) || (how->resolve&RESOLVE_NO_SYMLINKS)))
        return RAW4(openat2,base,path,how,sizeof(*how));
    if (kind==MD_PROC_MEMORY) {
        if (*ref.tail) return -ENOTDIR;
        unsigned long args[6]={(unsigned long)base,(uintptr_t)path,(uintptr_t)how,sizeof(*how)};
        return md_namespace_memory_open(ref.process,SYS_openat2,args);
    }
    if (md_proc_control_kind(kind)) {
        if (*ref.tail) return -ENOTDIR;
        if (kind==MD_PROC_NS_UNSUPPORTED) return -ENOENT;
        if (kind>=MD_PROC_NS_USER && kind<=MD_PROC_NS_CGROUP
                && (how->resolve&(RESOLVE_NO_SYMLINKS|RESOLVE_NO_MAGICLINKS))
                && (how->flags&(O_PATH|O_NOFOLLOW))!=(O_PATH|O_NOFOLLOW)) return -ELOOP;
        char probe_path[PATH_MAX];
        long error=md_copy(probe_path,sizeof(probe_path),path);
        if (error<0) return error;
        size_t length=md_length(probe_path);
        while (length && probe_path[length-1]!='/') --length;
        const char *leaf=kind<=MD_PROC_SETGROUPS ? "status" : ".";
        error=md_copy(probe_path+length,sizeof(probe_path)-length,leaf);
        if (error<0) return error;
        struct open_how probe=*how; probe.flags=O_PATH|O_CLOEXEC; probe.mode=0;
        long checked=RAW4(openat2,base,probe_path,&probe,sizeof(probe));
        if (checked<0) return checked;
        RAW1(close,checked);
        long result=md_proc_control_open(&ref,(int)how->flags,proc_namespace);
        return result==-EXDEV && (md_proc_native_task(kind) || md_proc_network_control(kind))
            ? RAW4(openat2,base,path,how,sizeof(*how)) : result;
    }
    if (kind==MD_PROC_MOUNTS || kind==MD_PROC_MOUNTINFO) {
        long checked=RAW4(openat2,base,path,how,sizeof(*how));
        if (checked<0) return checked;
        RAW1(close,checked);
        return mount_table_open(fs,&ref,identity,(int)how->flags);
    }
    if (kind==MD_PROC_FDINFO) {
        long checked=RAW4(openat2,base,path,how,sizeof(*how));
        if (checked<0) return checked;
        RAW1(close,checked);
        return fdinfo_open(fs,&ref,(int)how->flags,proc_namespace);
    }
    if (kind==MD_PROC_CMDLINE || kind==MD_PROC_AUXV) {
        if (*ref.tail) return -ENOTDIR;
        long checked=RAW4(openat2,base,path,how,sizeof(*how));
        if (checked<0) return checked;
        if (how->flags&O_PATH) return checked;
        RAW1(close,checked);
        return ref.kind==MD_PROC_FOREIGN
            ? RAW5(prctl,MD_GUEST_PROC_IMAGE,ref.process,kind,how->flags,0)
            : md_proc_image_open(fs,kind,(int)how->flags);
    }
    if (ref.kind==MD_PROC_FOREIGN) return -ENOTSUP;
    if (ref.kind && (how->resolve&(RESOLVE_NO_MAGICLINKS|RESOLVE_NO_SYMLINKS))) {
        if ((how->flags&(O_PATH|O_NOFOLLOW))==(O_PATH|O_NOFOLLOW) && !*ref.tail)
            return RAW4(openat2,base,path,how,sizeof(*how));
        return -ELOOP;
    }
    if (ref.kind==MD_PROC_EXE || ref.kind==MD_PROC_ROOT) return -ENOTSUP;
    return RAW4(openat2,base,path,how,sizeof(*how));
}

long md_namespace_proc_link_metadata(long nr,const unsigned long *a,const char *path,uint64_t mount) {
    struct md_proc_path ref=md_proc_path(path);
    enum md_proc_kind kind=ref.kind==MD_PROC_FOREIGN ? ref.foreign_kind : ref.kind;
    if (kind>=MD_PROC_NS_USER && kind<=MD_PROC_NS_CGROUP)
        return md_proc_control_link_metadata(&ref,nr,a,mount);
    /* The worker supplies a fully resolved no-follow proc dentry and its
     * selected mount. Kernel checks run under the actual caller, never under
     * the worker's identity or by making the caller dumpable. */
    if (nr==SYS_newfstatat)
        return RAW4(newfstatat,AT_FDCWD,path,a[2],a[3]);
    if (nr!=SYS_statx || !(a[2]&AT_SYMLINK_NOFOLLOW)) return -EINVAL;
    struct statx st;
    long r=RAW5(statx,AT_FDCWD,path,a[2],(a[3]&~(unsigned long)STATX_MNT_ID_UNIQUE)|STATX_MNT_ID,&st);
    if (r<0) return r;
    st.stx_mask|=STATX_MNT_ID; st.stx_mnt_id=mount;
    st.stx_attributes_mask|=STATX_ATTR_MOUNT_ROOT;
    st.stx_attributes&=~(uint64_t)STATX_ATTR_MOUNT_ROOT;
    return md_write_memory((void *)a[4],&st,sizeof(st));
}
long md_namespace_native_statx(const struct md_fs *fs, const unsigned long *args) {
    struct statx st;
    /* Publish ordinary mount IDs, never claim the kernel-global UNIQUE space. */
    long r=RAW5(statx,args[0],args[1],args[2],(args[3]&~(unsigned long)STATX_MNT_ID_UNIQUE)|STATX_MNT_ID,&st);
    if (r<0) return r;
    return md_namespace_native_statx_result(fs,args,&st);
}
long md_namespace_native_statx_result(const struct md_fs *fs,const unsigned long *args,struct statx *st) {
    /* Anonymous kernel files can report MNT_ID with zero: they have no mount
     * placement to translate. Preserve that result instead of rejecting stat. */
    if ((st->stx_mask & STATX_MNT_ID) && st->stx_mnt_id) {
        struct md_fs_request q={.operation=MD_FS_NATIVE_MOUNT,.directory={-1,-1},.offset=(int64_t)st->stx_mnt_id};
        struct md_fs_response out;
        long r=md_fs_call(fs->endpoint,5000,&q,&out);
        if (!r) r=out.result.error;
        if (r<0) return r;
        st->stx_mnt_id=out.result.info.mount_id;
    }
    return md_write_memory((void *)args[4],st,sizeof(*st));
}

static long descriptor_path(int base, char *host) {
    long n;
    if (base == AT_FDCWD) {
        n = RAW2(getcwd, host, PATH_MAX);
        if (n < 0) return n;
    } else {
        if (base < 0) return -EBADF;
        char link[64] = "/proc/thread-self/fd/";
        md_decimal(link + md_length(link), (unsigned)base);
        n = RAW4(readlinkat, AT_FDCWD, link, host, PATH_MAX - 1);
        /* A missing proc link is not a missing relative pathname. Preserve
         * EBADF for a closed dirfd without probing valid descriptors twice. */
        if (n == -ENOENT && RAW3(fcntl, base, F_GETFD, 0) == -EBADF) return -EBADF;
        if (n < 0) return n;
        if (n == PATH_MAX - 1) return -ENAMETOOLONG;
        host[n] = 0;
    }
    return 0;
}
long md_namespace_native_descriptor(int base) {
    char host[PATH_MAX];
    long r=descriptor_path(base,host);
    return r<0 ? r : md_host_path(host);
}
long md_namespace_relative_mount(const struct md_fs *fs, int base, char *path) {
    if (!*path || *path == '/') return 0;
    char host[PATH_MAX];
    long n=descriptor_path(base,host);
    if (n<0) return n;
    if (!md_host_path(host)) return 0;
    /* A retained directory remains usable after chroot/pivot. In a scoped
     * root its absolute kernel name is not a guest pathname: keep the dirfd
     * operand so the namespace owner resolves its provenance instead. */
    if (fs->managed_paths) return 0;
    struct stat info;
    n = base == AT_FDCWD ? RAW4(newfstatat, base, ".", &info, 0) : RAW2(fstat, base, &info);
    if (n < 0) return n;
    if (!S_ISDIR(info.st_mode)) return -ENOTDIR;
    n = md_append(host, sizeof(host), "/");
    if (!n) n = md_append(host, sizeof(host), path);
    return n ? n : md_copy(path, PATH_MAX, host);
}

static long link_result(const char *text, const unsigned long *a) {
    if (!a[3]) return -EINVAL;
    size_t n = md_length(text);
    if (n > a[3]) n = a[3];
    long r = md_write_memory((void *)a[2], text, n);
    return r < 0 ? r : (long)n;
}
static int link_operation(long nr, const unsigned long *a) {
    switch (nr) {
    case SYS_openat: return (a[2] & O_NOFOLLOW) || ((a[2] & (O_CREAT | O_EXCL)) == (O_CREAT | O_EXCL));
    case SYS_newfstatat: case SYS_faccessat2: return a[3] & AT_SYMLINK_NOFOLLOW;
    case SYS_statx: return a[2] & AT_SYMLINK_NOFOLLOW;
    case SYS_fchownat: return a[4] & AT_SYMLINK_NOFOLLOW;
    case SYS_utimensat: return a[3] & AT_SYMLINK_NOFOLLOW;
    case SYS_inotify_add_watch: return a[2] & IN_DONT_FOLLOW;
    case SYS_lsetxattr: case SYS_lgetxattr: case SYS_llistxattr: case SYS_lremovexattr:
    case SYS_mkdirat: case SYS_unlinkat: case SYS_mknodat: case SYS_symlinkat:
        return 1;
    default: return 0;
    }
}
int md_namespace_proc_borrow(const struct md_proc_path *ref, int *fd) {
    if (ref->process!=(int)RAW0(gettid)) return 0;
    if (ref->kind==MD_PROC_FD) *fd=ref->descriptor;
    else if (ref->kind==MD_PROC_CWD) *fd=AT_FDCWD;
    else return 0;
    return 1;
}
long md_namespace_paths_resolve(const struct md_fs *fs,const struct md_fs_request *input,
        struct md_fs_response *out) {
    struct md_fs_request request=*input, *q=&request;
    char selected[2][PATH_MAX], relative[2][PATH_MAX];
    unsigned captured=0;
    int entries=q->operation==MD_FS_LINK || q->operation==MD_FS_RENAME || q->operation==MD_FS_SOCKET_BIND;
    for (;;) {
        long r=md_namespace_request(fs,q,out);
        if (r!=-EREMOTE) return r;
        unsigned index=out->result.host_path-1;
        if (index>1 || (captured&(1U<<index))) return -ELOOP;
        captured|=1U<<index;
        /* A delegated lookup has not executed the mutation. Resolve its input
         * through the same table-owned capture used by mount operations. */
        struct md_proc_path ref=md_proc_path(out->data);
        enum md_proc_kind kind=ref.kind==MD_PROC_FOREIGN ? ref.foreign_kind : ref.kind;
        int bind_source=(q->operation==MD_FS_MOUNT && index==1 && q->mode==MD_FS_MOUNT_BIND)
            || (q->operation==MD_FS_OPEN_TREE && index==0);
        if (md_proc_control_kind(kind) && !bind_source) return -EOPNOTSUPP;
        /* Entry mutations must retain the final name, not follow it and
         * mutate a proc magic link's target. Only directory suffixes enter
         * the guest namespace; proc entries themselves remain unsupported. */
        if (entries && ((kind!=MD_PROC_FD && kind!=MD_PROC_CWD && kind!=MD_PROC_ROOT)
                || !ref.tail || !*ref.tail)) return -EOPNOTSUPP;
        int fd=-1;
        if (md_namespace_proc_borrow(&ref,&fd)) {
            long control=md_proc_control_known(fd);
            if (control<0) return control;
            if (control && !bind_source && !(q->operation==MD_FS_MOUNT && index==0))
                return entries ? -ENOTDIR : -EOPNOTSUPP;
            const char *tail=ref.tail;
            int trailing=*tail!=0;
            while (*tail=='/') ++tail;
            r=md_copy(relative[index],sizeof(relative[index]),*tail ? tail : trailing ? "." : "");
            if (r<0) return r;
            q->directory[index]=fd; q->path[index]=relative[index];
        } else if (ref.kind==MD_PROC_ROOT && ref.process==(int)RAW0(gettid)) {
            r=md_copy(relative[index],sizeof(relative[index]),*ref.tail ? ref.tail : "/");
            if (r<0) return r;
            q->directory[index]=AT_FDCWD; q->path[index]=relative[index];
        } else {
            int directory=entries || ((kind==MD_PROC_FD || kind==MD_PROC_CWD || kind==MD_PROC_ROOT)
                && ref.tail && *ref.tail);
            const char *tail=directory ? ref.tail : "";
            while (*tail=='/') ++tail;
            r=md_copy(relative[index],sizeof(relative[index]),directory ? (*tail ? tail : ".") : "");
            if (r<0) return r;
            if (directory) out->data[ref.anchor_length]=0;
            r=md_proc_task_path(out->data,selected[index],sizeof(selected[index]));
            if (r<0) return r;
            q->directory[index]=-1; q->path[index]=relative[index];
            q->capture[index]=(struct md_fs_task_capture){selected[index],O_PATH|(directory ? O_DIRECTORY : 0)};
        }
        if (q->operation==MD_FS_SOCKET_ADDRESS && !*q->path[index]) q->path[index]=NULL;
    }
}
long md_namespace_paths_request(const struct md_fs *fs,struct md_fs_request *q) {
    struct md_fs_response out;
    long r=md_namespace_paths_resolve(fs,q,&out);
    return r<0 || (q->operation!=MD_FS_OPEN_TREE && q->operation!=MD_FS_FSPICK)
        ? r : md_namespace_open_result(&out.result,q->flags);
}
static long host_readlink(const struct md_fs *fs, const struct md_proc_path *ref,
        const char *path, const unsigned long *a) {
    if (!a[3]) return -EINVAL;
    char text[PATH_MAX];
    long n = RAW4(readlinkat, AT_FDCWD, path, text, sizeof(text) - 1);
    if (n < 0) return n;
    text[n] = 0;
    int fd=-1;
    int borrowed=md_namespace_proc_borrow(ref,&fd);
    char selected[PATH_MAX];
    if (!borrowed && (n=md_proc_task_path(path,selected,sizeof(selected)))<0) return n;
    struct md_fs_request q = {.operation = MD_FS_PATH, .mode=MD_FS_PATH_DISPLAY, .directory = {fd, -1},
        .capture={{borrowed ? NULL : selected,borrowed ? 0 : O_PATH}}};
    struct md_fs_response out;
    long r = md_namespace_request(fs, &q, &out);
    if (!r) r = md_copy(text, sizeof(text), out.data);
    if (r < 0 && r != -EXDEV && (borrowed || out.delivery!=MD_FS_NOT_SENT)) return r;
    return link_result(text, a);
}
static long target_call(const struct md_fs *fs, long nr, const unsigned long *a, struct md_namespace_operand operand) {
    int fd=operand.descriptor;
    unsigned long args[6]; memcpy(args,a,sizeof(args));
    switch (nr) {
    case SYS_newfstatat: case SYS_statx:
        args[0]=(unsigned long)fd; args[1]=(unsigned long)"";
        args[nr==SYS_statx ? 2 : 3]|=AT_EMPTY_PATH;
        return md_namespace_path_call(fs,nr,args,operand,"");
    case SYS_chdir: case SYS_truncate: case SYS_statfs:
        return md_namespace_path_call(fs,nr,args,operand,"");
    case SYS_faccessat: case SYS_faccessat2:
    case SYS_fchmodat: case SYS_fchmodat2:
    case SYS_fchownat: case SYS_utimensat: {
        long operation=nr==SYS_faccessat ? SYS_faccessat2 : nr==SYS_fchmodat ? SYS_fchmodat2 : nr;
        unsigned flags_index=nr==SYS_fchownat ? 4 : 3;
        args[0]=(unsigned long)fd; args[1]=(unsigned long)"";
        args[flags_index]=(nr==SYS_faccessat || nr==SYS_fchmodat ? 0 : args[flags_index])|AT_EMPTY_PATH;
        return md_namespace_path_call(fs,operation,args,operand,"");
    }
    case SYS_setxattr: case SYS_getxattr: case SYS_listxattr: case SYS_removexattr:
        return md_namespace_xattr(fs,nr,operand,"",args);
    case SYS_inotify_add_watch: {
        struct md_fs_request q={.operation=MD_FS_WATCH_ADD,.directory={(int)a[0],fd},.capture={[1]=operand.capture},.flags=(unsigned)a[2]};
        struct md_fs_response out;
        long result=md_namespace_request(fs,&q,&out);
        return result<0 ? result : out.result.position;
    }
    default: return -ENOTSUP;
    }
}
static long native_call(const struct md_fs *fs, long nr, const unsigned long *a) {
    if (nr==SYS_inotify_add_watch) {
        char selected[PATH_MAX];
        long result=md_proc_task_path((const char *)a[1],selected,sizeof(selected));
        if (result<0) return result;
        struct md_fs_request q={.operation=MD_FS_WATCH_ADD,.directory={(int)a[0],-1},.flags=(unsigned)a[2],
            .capture={[1]={selected,O_PATH|(a[2]&IN_DONT_FOLLOW ? O_NOFOLLOW : 0)}}};
        struct md_fs_response out;
        result=md_namespace_request(fs,&q,&out);
        return result<0 ? result : out.result.position;
    }
    return nr==SYS_statx ? md_namespace_native_statx(fs,a)
        : md_raw(nr,a[0],a[1],a[2],a[3],a[4],a[5]);
}
static long host_call(const struct md_fs *fs, const char *exe, long nr, const unsigned long *a,
                            unsigned path_index, const char *path, uint64_t proc_namespace) {
    struct md_proc_path ref = md_proc_path(path);
    enum md_proc_kind control=ref.kind==MD_PROC_FOREIGN ? ref.foreign_kind : ref.kind;
    /* Proc aliases are ordinary links, not their target's process controls.
     * Keep no-follow operations on that dentry in the caller's kernel view. */
    if (ref.ordinary_link && !*ref.tail
            && (nr==SYS_readlinkat || link_operation(nr,a))) {
        unsigned long args[6]; memcpy(args,a,sizeof(args)); args[path_index]=(unsigned long)path;
        return native_call(fs,nr,args);
    }
    if (control==MD_PROC_MEMORY) {
        if (*ref.tail) return -ENOTDIR;
        unsigned long args[6]; memcpy(args,a,sizeof(args)); args[path_index]=(unsigned long)path;
        if (nr==SYS_openat) return md_namespace_memory_open(ref.process,nr,args);
        return native_call(fs,nr,args);
    }
    if (control==MD_PROC_FDINFO && (proc_namespace || ref.kind!=MD_PROC_FOREIGN) && nr==SYS_openat)
        return fdinfo_open(fs,&ref,(int)a[2],proc_namespace);
    if (control==MD_PROC_FDINFO) ref.kind=MD_PROC_NONE;
    if (md_proc_control_kind(control)) {
        if (control==MD_PROC_NS_UNSUPPORTED) return -ENOENT;
        if (*ref.tail) return -ENOTDIR;
        /* Process text is virtualized, but its inode and watch lifetime still
         * belong to procfs. Capture the native target under caller authority. */
        int process_watch=nr==SYS_inotify_add_watch && md_proc_native_task(control);
        long result=process_watch ? -EXDEV
            : nr==SYS_openat ? md_proc_control_open(&ref,(int)a[2],proc_namespace)
            : md_proc_control_metadata(&ref,nr,a);
        if (result!=-EXDEV || (!md_proc_native_task(control) && !md_proc_network_control(control))) return result;
        ref.kind=MD_PROC_NONE;
    }
    if (control==MD_PROC_MOUNTS || control==MD_PROC_MOUNTINFO) {
        if (*ref.tail) return -ENOTDIR;
        if (nr==SYS_openat) return mount_table_open(fs,&ref,path,(int)a[2]);
        ref.kind=MD_PROC_NONE;
    }
    /* Image bytes need the selected process, but metadata and access checks
     * belong to the native proc inode, just as they do for our own image. */
    if (control==MD_PROC_CMDLINE || control==MD_PROC_AUXV) {
        if (*ref.tail) return -ENOTDIR;
        if (nr==SYS_openat && (a[2]&O_PATH)) ref.kind=MD_PROC_NONE;
        else if (nr!=SYS_openat) ref.kind=control;
    }
    if (ref.kind == MD_PROC_FOREIGN) {
        if (ref.foreign_kind==MD_PROC_ROOT || ref.foreign_kind==MD_PROC_CWD) {
            if (!*ref.tail && link_operation(nr,a)) {
                unsigned long args[6]; memcpy(args,a,sizeof(args));
                args[path_index]=(unsigned long)path;
                return native_call(fs,nr,args);
            }
            char anchor[PATH_MAX];
            size_t length=*ref.tail ? ref.anchor_length : md_length(path);
            memcpy(anchor,path,length); anchor[length]=0;
            struct md_namespace_operand operand={.descriptor=-1,.capture={anchor,O_PATH|O_DIRECTORY}};
            if (!*ref.tail && nr==SYS_readlinkat) {
                if (ref.foreign_kind==MD_PROC_CWD) {
                    long result=md_proc_control_metadata(&ref,nr,a);
                    return result!=-EXDEV ? result : host_readlink(fs,&ref,path,a);
                }
                struct md_fs_request q={.operation=MD_FS_PATH,.mode=MD_FS_PATH_DISPLAY,
                    .directory={-1,-1},.capture={operand.capture}};
                struct md_fs_response out;
                long result=md_namespace_request(fs,&q,&out);
                return result<0 ? result : link_result(out.data,a);
            }
            const char *relative=ref.tail;
            while (*relative=='/') ++relative;
            if (!*ref.tail && nr!=SYS_openat) return target_call(fs,nr,a,operand);
            return md_namespace_path_call(fs,nr,a,operand,*relative ? relative : ".");
        }
        if (*ref.tail) return -ENOTSUP;
        if (ref.foreign_kind==MD_PROC_FD
                || (ref.foreign_kind==MD_PROC_EXE && (nr==SYS_newfstatat || nr==SYS_statx))) {
            if (nr!=SYS_newfstatat && nr!=SYS_statx && nr!=SYS_readlinkat) return -ENOTSUP;
            long result=md_proc_control_metadata(&ref,nr,a);
            if (result!=-EXDEV) return result;
            /* Reuse the retained-object path below, without treating another
             * process's descriptor number or executable as caller-owned. */
            ref.kind=ref.foreign_kind;
        }
    }
    if (ref.kind==MD_PROC_FOREIGN) {
        if (ref.foreign_kind!=MD_PROC_EXE && ref.foreign_kind!=MD_PROC_CMDLINE
                && ref.foreign_kind!=MD_PROC_AUXV) return -ENOTSUP;
        if (nr!=SYS_openat && nr!=SYS_readlinkat) return -ENOTSUP;
        if (nr==SYS_readlinkat && ref.foreign_kind!=MD_PROC_EXE) return -ENOTSUP;
        if (nr==SYS_readlinkat && !a[3]) return -EINVAL;
        if (nr==SYS_readlinkat)
            return md_fs_buffer_proc_link(ref.process,(void *)a[2],a[3]);
        if (ref.foreign_kind==MD_PROC_EXE) {
            return md_proc_executable_capture(fs,ref.process,(int)a[2]);
        }
        /* Cmdline and auxv have different native access rules. Check the
         * selected proc entry as the caller before requesting virtual bytes. */
        long checked=RAW4(openat,AT_FDCWD,path,a[2],a[3]);
        if (checked<0) return checked;
        RAW1(close,checked);
        return RAW5(prctl,MD_GUEST_PROC_IMAGE,ref.process,ref.foreign_kind,a[2],0);
    }
    if (ref.kind == MD_PROC_CMDLINE || ref.kind == MD_PROC_AUXV) {
        if (*ref.tail) return -ENOTDIR;
        if (nr == SYS_openat) return md_proc_image_open(fs, ref.kind, (int)a[2]);
        ref.kind = MD_PROC_NONE;
    }
    unsigned long args[6];
    memcpy(args, a, sizeof(args));
    args[path_index] = (unsigned long)path;
    int tail = ref.kind && *ref.tail;
    int native = !ref.kind || (!tail && link_operation(nr, a));
    if (!native && ref.kind==MD_PROC_FD && ref.process==(int)RAW0(gettid)) {
        long known=md_descriptor_kind(ref.descriptor);
        if (known<0) return known;
        if (known==MD_DESCRIPTOR_FILESYSTEM) known=0;
        if (known==MD_DESCRIPTOR_ANONYMOUS) {
            if (tail) return -ENOTDIR;
            if (nr==SYS_openat) return md_namespace_proc_reopen(fs,ref.descriptor,(int)a[2]&~(O_CREAT|O_EXCL));
            if (nr!=SYS_readlinkat) return md_descriptor_metadata(fs,ref.descriptor,nr,a);
            known=0;
        }
        if (known) {
            if (tail) return -ENOTDIR;
            if (nr==SYS_openat) return md_proc_control_reopen(ref.process,ref.descriptor,(int)a[2]&~(O_CREAT|O_EXCL));
            if (nr!=SYS_readlinkat || md_proc_file_namespace((enum md_proc_file_kind)(known-1))) {
                long result=md_proc_control_metadata(&ref,nr,a);
                if (result!=-EXDEV) return result;
            }
        }
    }
    if (nr == SYS_readlinkat && !tail) {
        if (ref.kind == MD_PROC_EXE) return link_result(exe, a);
        if (ref.kind == MD_PROC_ROOT) return link_result("/", a);
        if (ref.ordinary_link) return RAW4(readlinkat, AT_FDCWD, path, a[2], a[3]);
        return host_readlink(fs, &ref, path, a);
    }
    if (native) return native_call(fs,nr,args);
    if (ref.kind==MD_PROC_ROOT) {
        char root[PATH_MAX]="/";
        long error=tail ? md_copy(root,sizeof(root),ref.tail) : 0;
        return error<0 ? error : md_namespace_path_call(fs,nr,args,md_namespace_descriptor(AT_FDCWD),root);
    }
    if (!tail && ref.kind==MD_PROC_FD && ref.process==(int)RAW0(gettid) && nr!=SYS_openat)
        return target_call(fs,nr,args,md_namespace_descriptor(ref.descriptor));
    if (!tail && nr == SYS_openat) {
        if (ref.kind == MD_PROC_EXE) return md_proc_executable_open(fs, exe, (int)a[2]);
        int flags=(int)a[2];
        if ((flags&O_CREAT) && (flags&O_DIRECTORY)) return -EINVAL;
        if (ref.kind==MD_PROC_FD && ref.process==(int)RAW0(gettid))
            return md_namespace_proc_reopen(fs,ref.descriptor,flags&~(O_CREAT|O_EXCL));
        if (ref.kind==MD_PROC_CWD && ref.process==(int)RAW0(gettid))
            return md_namespace_open(fs,AT_FDCWD,".",flags&~(O_CREAT|O_EXCL),0);
        /* The followed magic link already selected an existing object. Creation
         * and exclusive-link semantics were handled above, not by its backing. */
        char selected[PATH_MAX];
        long result=md_proc_task_path(path,selected,sizeof(selected));
        if (result<0) return result;
        struct md_fs_request q={.operation=MD_FS_REOPEN,.directory={-1,-1},
            .capture={{selected,O_PATH}},.flags=flags&~(O_CREAT|O_EXCL),.mode=MD_FS_REOPEN_NATIVE};
        struct md_fs_response out;
        result=md_namespace_request(fs,&q,&out);
        if (result<0) return result;
        return md_namespace_open_result(&out.result,flags&~(O_CREAT|O_EXCL));
    }
    char anchor[PATH_MAX],selected[PATH_MAX];
    size_t length = tail ? ref.anchor_length : md_length(path);
    memcpy(anchor, path, length); anchor[length] = 0;
    struct md_namespace_operand operand=md_namespace_descriptor(-1);
    int owned=-1;
    int local_image=ref.kind==MD_PROC_EXE && ref.foreign_kind!=MD_PROC_EXE;
    int image=local_image && fs->image && fs->image->executable_object[0];
    if (image) operand.descriptor=MD_NAMESPACE_EXECUTABLE;
    else if (!native && local_image) {
        long fd=md_proc_executable_open(fs,exe,O_PATH|O_CLOEXEC|(tail ? O_DIRECTORY : 0));
        if (fd<0) return fd;
        owned=operand.descriptor=(int)fd;
    } else if (!md_namespace_proc_borrow(&ref,&operand.descriptor)) {
        long error=md_proc_task_path(anchor,selected,sizeof(selected));
        if (error<0) return error;
        operand.capture=(struct md_fs_task_capture){selected,O_PATH|(tail ? O_DIRECTORY : 0)};
    }
    long result;
    if (tail) {
        const char *relative=ref.tail;
        while (*relative=='/') ++relative;
        result=md_namespace_path_call(fs,nr,args,operand,*relative ? relative : ".");
    } else result=target_call(fs,nr,args,operand);
    if (result==-EXDEV) result=native_call(fs,nr,args);
    if (owned>=0) RAW1(close,owned);
    return result;
}
long md_namespace_host_call(const struct md_fs *fs,const char *exe,long nr,const unsigned long *a,
        unsigned path_index,const char *path,uint64_t proc_namespace) {
    long result=host_call(fs,exe,nr,a,path_index,path,proc_namespace);
    if (result==-EBADF && nr!=SYS_inotify_add_watch) {
        /* Borrowing a proc target is an internal FD operation. A closed target
         * is a missing pathname to the caller, not an invalid syscall dirfd. */
        struct md_proc_path ref=md_proc_path(path);
        if (ref.kind==MD_PROC_FD || (ref.kind==MD_PROC_FOREIGN && ref.foreign_kind==MD_PROC_FD))
            result=-ENOENT;
    }
    return result;
}
