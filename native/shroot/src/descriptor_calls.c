#define _GNU_SOURCE
#include "descriptor_calls.h"
#include "namespace.h"
#include "file_metadata.h"
#include "adapter_buffer.h"
#include "fd_client.h"
#include "guest_execution.h"
#include "fd_filter.h"
#include "watch_calls.h"
#include "interception.h"
#include "proc_control.h"
#include "raw.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/stat.h>
#include <sys/sysmacros.h>

long md_descriptor_kind(int fd) {
    return md_fd_control(MD_FD_KIND,0,fd,0);
}
long md_descriptor_duplicate(const struct md_fs *fs, long nr, const unsigned long *a) {
    unsigned command=(unsigned)a[1];
    if (nr==SYS_fcntl && command!=F_DUPFD && command!=F_DUPFD_CLOEXEC)
        return md_raw(nr,a[0],a[1],a[2],a[3],a[4],a[5]);
    unsigned required;
    for (;;) {
        struct md_fd_observation view=md_fd_observe((int)a[0]);
        if (view.selectors<0) return view.selectors;
        long watched=view.selectors ? 0 : md_watch_contains(fs,(int)a[0]);
        /* Provider discovery captures its own FD inputs. Do not nest it under
         * the mutation turn. A changed table retries discovery before any
         * duplicate/filter side effect; once admitted, the turn spans both. */
        long current=md_fd_control(MD_FD_DUPLICATE_BEGIN,view.generation,0,0);
        if (current==-EAGAIN) continue;
        if (current<0) return current;
        if (watched<0) {
            md_fd_control(MD_FD_DUPLICATE_END,0,0,0); return watched;
        }
        required=(unsigned)current|(watched>0 ? 1U<<MD_FD_SELECTOR_WATCH : 0);
        break;
    }
    long result=0;
    if (nr==SYS_dup3 && (int)a[1]>=0 && (int)a[0]!=(int)a[1] && !((unsigned)a[2]&~O_CLOEXEC)) {
        /* Admission failure must not replace the caller's destination. */
        result=md_fd_filter_arm_mask(required,(int)a[1]);
    }
    if (!result) result=md_guest_native_call(nr,a);
    if (result>=0 && nr!=SYS_dup3) {
        long activation=md_fd_filter_arm_mask(required,(int)result);
        if (activation) {
            md_guest_native_call(SYS_close,(unsigned long[6]){(unsigned long)result});
            result=activation;
        }
    }
    long released=md_fd_control(MD_FD_DUPLICATE_END,0,0,0);
    return result<0 ? result : released<0 ? released : result;
}
long md_descriptor_pair(long nr, const unsigned long *a) {
    int ends[2]={-1,-1};
    unsigned long output=a[nr==SYS_pipe2 ? 0 : 3];
    long result=md_fd_control(MD_FD_PAIR_BEGIN,0,0,0);
    if (result<0) return result;
    /* Kernel creation publishes directly to the application's output. A fatal
     * signal cannot strand endpoints in a private adapter stack with surviving
     * CLONE_FILES peers. Read back only after successful native publication. */
    result=md_raw(nr,a[0],a[1],a[2],a[3],a[4],a[5]);
    long observed=result ? result : md_read_memory(ends,(const void *)output,sizeof(ends));
    if (observed<0) ends[0]=ends[1]=-1;
    long published=md_fd_control(MD_FD_PAIR_END,0,ends[0],ends[1]);
    if (!result && published<0) result=published;
    /* Concurrent removal of a successful output does not undo the native
     * syscall. Unknown numbers must never be used to close or annotate FDs. */
    if (result<0) for (unsigned i=0;i<2;++i) if (ends[i]>=0) RAW1(close,ends[i]);
    return result;
}
long md_descriptor_metadata(const struct md_fs *fs,int fd, long nr, const unsigned long *a) {
    long flags=RAW2(fcntl,fd,F_GETFL);
    if (flags<0) return flags;
    if ((flags&O_PATH) && (nr==SYS_fchown || nr==SYS_fchmod)) return -EBADF;
    if (nr==SYS_getdents64 || nr==SYS_fchdir) return -ENOTDIR;
    if (nr==SYS_fstatfs || nr==SYS_statfs) {
        long result=RAW2(fstatfs,fd,a[1]);
        return result==-EACCES || result==-EPERM ? md_namespace_fstatfs(fs,fd,(void *)a[1]) : result;
    }
    if (nr!=SYS_fstat && nr!=SYS_newfstatat && nr!=SYS_statx && nr!=SYS_fchown && nr!=SYS_fchownat
            && nr!=SYS_fchmod && nr!=SYS_fchmodat && nr!=SYS_fchmodat2) return -EOPNOTSUPP;
    if (nr==SYS_fchownat && (a[4]&~(AT_EMPTY_PATH|AT_SYMLINK_NOFOLLOW))) return -EINVAL;
    if (nr==SYS_fchmodat2 && (a[3]&~(AT_EMPTY_PATH|AT_SYMLINK_NOFOLLOW))) return -EINVAL;
    if (nr==SYS_newfstatat && (a[3]&~(AT_EMPTY_PATH|AT_SYMLINK_NOFOLLOW|AT_NO_AUTOMOUNT))) return -EINVAL;
    if (nr==SYS_statx && ((a[2]&~(AT_EMPTY_PATH|AT_SYMLINK_NOFOLLOW|AT_NO_AUTOMOUNT|AT_STATX_SYNC_TYPE))
            || (a[2]&AT_STATX_SYNC_TYPE)==AT_STATX_SYNC_TYPE || (a[3]&STATX__RESERVED))) return -EINVAL;
    long memory=md_adapter_buffer_take();
    if (memory<0) return memory;
    struct stat *st=(void *)memory;
    long result=RAW2(fstat,fd,st);
    if (!result) result=md_guest_buffer_control(MD_BUFFER_DESCRIPTOR_META,(unsigned)fd,nr);
    if (!result && (nr==SYS_fstat || nr==SYS_newfstatat))
        result=md_write_memory((void *)a[nr==SYS_fstat ? 1 : 2],st,sizeof(*st));
    else if (!result && nr==SYS_statx) {
        struct statx value;
        md_stat_statx(st,&value);
        result=md_write_memory((void *)a[4],&value,sizeof(value));
    }
    long released=md_adapter_buffer_release();
    return result ? result : released;
}
