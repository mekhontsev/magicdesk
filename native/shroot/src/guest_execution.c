#define _GNU_SOURCE
#include "guest_execution.h"
#include "bootstrap.h"
#include "file_calls.h"
#include "namespace.h"
#include "socket_calls.h"
#include "network_calls.h"
#include "socket_ancillary.h"
#include "raw.h"
#include "interception.h"
#include "pid_calls.h"
#include "terminal_calls.h"
#include "proc_control.h"
#include "watch_calls.h"
#include "fd_filter.h"
#include "descriptor_calls.h"
#include "adapter_buffer.h"
#include "filesystem_ioctl.h"
#include "epoll_calls.h"
#include "poll_calls.h"
#include "clone_call.h"
#include "identity_transfer.h"
#include "signal_calls.h"
#include <errno.h>
#include <fcntl.h>
#include <sys/prctl.h>
#include <sys/utsname.h>

static long execute_files(const struct md_fs *fs,const struct md_guest_execution *call) {
    long nr=call->number;
    const unsigned long *args=call->args;
    unsigned long a0=args[0],a1=args[1];
    if (md_identity_transfer_call(nr)) return md_identity_dispatch(nr,args);
    if (nr==SYS_rt_sigpending || (nr==SYS_rt_sigprocmask && !a1)) return md_signal_query_call(nr,args);
    if (nr==SYS_get_robust_list || (nr==SYS_prctl && a0==PR_GET_CHILD_SUBREAPER))
        return md_process_query(nr,args);
    if (nr==SYS_wait4 || nr==SYS_waitid) return md_process_wait_result(nr,args);
    if (nr==SYS_clone3) return md_clone3_prepare(a0,a1);
    if (nr==SYS_epoll_ctl || nr==SYS_epoll_pwait || nr==SYS_epoll_pwait2) return md_epoll_call(nr,args);
    if (nr==SYS_ppoll || nr==SYS_pselect6 || nr==SYS_rt_sigsuspend) return md_poll_call(nr,args,call->prepared);
    if (nr==SYS_seccomp || nr==SYS_prctl) {
        md_cooperative_revoke();
        long result=md_adapter_readonly_install();
        return result ? result : md_fd_filter_prepare_sandbox();
    }
    if (nr==SYS_unshare || nr==SYS_clone) return md_pid_filter_install();
    if (nr==SYS_setns) return md_setns(args);
    if (nr==SYS_kill && (int)a0<=0) return md_pid_signal_group((int)a0,(int)a1);
    if (nr==SYS_rt_sigqueueinfo || nr==SYS_rt_tgsigqueueinfo) return md_signal_queue_call(nr,args);
    if (nr==SYS_pidfd_send_signal) return md_pidfd_signal(args);
    if (nr==SYS_ioctl && md_pidfd_namespace_command((unsigned)a1)) return md_pidfd_namespace(args);
    if (nr==SYS_ioctl && md_terminal_identity_command((unsigned)a1))
        return md_pid_terminal(args);
    if (call->control>=0 && (nr==SYS_read || nr==SYS_write || nr==SYS_readv || nr==SYS_writev
            || nr==SYS_pread64 || nr==SYS_pwrite64 || nr==SYS_preadv || nr==SYS_pwritev
            || nr==SYS_preadv2 || nr==SYS_pwritev2 || nr==SYS_lseek || nr==SYS_ioctl || nr==SYS_splice
            || nr==SYS_ftruncate || nr==SYS_fsync || nr==SYS_fdatasync || nr==SYS_mmap || nr==SYS_getdents64))
        return md_proc_control_call(nr,args,call->control);
    if (nr==SYS_ioctl && md_terminal_attributes_command((unsigned)a1))
        return md_terminal_attributes(args);
    if (nr==SYS_ioctl && md_filesystem_ioctl_command((unsigned)a1))
        return fs->endpoint[0] ? md_namespace_call(fs,md_executable,nr,args) : RAW3(ioctl,a0,a1,args[2]);
    if (nr==SYS_uname || nr==SYS_sethostname || nr==SYS_setdomainname) {
        if (nr!=SYS_uname && a1>=sizeof(((struct utsname *)0)->nodename)) return -EINVAL;
        if (nr==SYS_uname) {
            struct md_adapter_input input=md_adapter_buffer_begin(MD_BUFFER_UTS,0);
            if (!input.buffer) return input.value;
            long result=input.value;
            if (!result) result=md_write_memory((void *)a0,input.buffer,sizeof(struct utsname));
            long released=md_adapter_buffer_release();
            return result ? result : released;
        }
        long memory=md_adapter_buffer_take();
        if (memory<0) return memory;
        long result=md_read_memory((void *)memory,(void *)a0,a1);
        if (!result) result=md_guest_buffer_control(MD_BUFFER_UTS,0,0);
        long released=md_adapter_buffer_release();
        return result ? result : released;
    }
    if (nr==SYS_shmget || nr==SYS_shmat || nr==SYS_shmdt || nr==SYS_shmctl)
        return md_shm_dispatch(nr,args);
    if (nr==SYS_semget || nr==SYS_semctl || nr==SYS_semop || nr==SYS_semtimedop
            || nr==SYS_msgget || nr==SYS_msgctl || nr==SYS_msgsnd || nr==SYS_msgrcv)
        return md_ipc_dispatch(nr,args);
    if (nr==SYS_read || nr==SYS_readv || nr==SYS_ioctl || nr==SYS_splice
            || nr==SYS_tee || nr==SYS_sendfile) return md_watch_read_local(fs,nr,args);
    if (nr==SYS_dup || nr==SYS_dup3 || nr==SYS_fcntl)
        return md_descriptor_duplicate(fs,nr,args);
    if (nr==SYS_inotify_init1 || nr==SYS_inotify_rm_watch)
        return md_watch_call(fs,nr,args);
    if (nr==SYS_recvmmsg) return md_socket_ancillary_batch(fs,args,call->prepared);
    switch (nr) {
        case SYS_chroot: return md_namespace_call(fs,md_executable,nr,args);
#define CALL(name) case SYS_##name:
        MD_FILE_CALLS(CALL)
            return md_file_call(fs,md_executable,nr,args);
        MD_SOCKET_CALLS(CALL)
            return md_socket_call(fs,md_executable,nr,args,call->prepared);
#undef CALL
        case SYS_execve: return md_guest_execat(fs,AT_FDCWD,(void *)a0,(void *)a1,(void *)args[2],0,call->prepared);
        case SYS_execveat: return md_guest_execat(fs,a0,(void *)a1,(void *)args[2],(void *)args[3],args[4],call->prepared);
        default: return -ENOSYS;
    }
}
/* FS selection is task-local with CLONE_VM and independently unshared CLONE_FS.
 * Keep the large md_fs copy out of ordinary execution's stack frame. */
__attribute__((noinline)) static long execute_scoped(const struct md_guest_execution *call) {
    struct md_fs fs=md_files;
    fs.managed_paths=1;
    return execute_files(&fs,call);
}
long md_guest_execute(const struct md_guest_execution *call) {
    long nr=call->number;
    const unsigned long *args=call->args;
    unsigned policy=call->policy;
    if (policy&MD_DISPATCH_DEVICE_EVENTS)
        return nr==SYS_socket ? md_network_open(args) : md_device_event_call(nr,args);
    if (policy&MD_DISPATCH_NETWORK_DATA) {
        if (nr==SYS_recvmmsg) return md_socket_ancillary_batch(&md_files,args,call->prepared);
        return md_network_data_call(nr,args);
    }
    if (policy&MD_DISPATCH_NETWORK_CONTROL) return md_network_call(nr,args);
    if ((policy&MD_DISPATCH_NETWORK_SCOPED) && nr==SYS_socket) return md_network_open(args);
    if (policy&MD_DISPATCH_OFFLINE_SOCKET) {
        long error=md_socket_offline_error(nr,args);
        if (error) return error;
    }
    if ((policy&MD_DISPATCH_NETWORK_SCOPED) && nr==SYS_socketpair) {
        struct md_fs fs=md_files; fs.managed_paths=!!(policy&MD_DISPATCH_PATHS);
        return md_socket_pair(&fs,args,1);
    }
    return policy&MD_DISPATCH_PATHS ? execute_scoped(call) : execute_files(&md_files,call);
}
