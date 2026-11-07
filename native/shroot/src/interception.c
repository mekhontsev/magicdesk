#define _GNU_SOURCE
#include "bootstrap.h"
#include "file_calls.h"
#include "namespace.h"
#include "socket_calls.h"
#include "network_calls.h"
#include "socket_ancillary.h"
#include "raw.h"
#include "interception.h"
#include "interception_program.h"
#include "pid_calls.h"
#include "linux_abi.h"
#include "terminal_calls.h"
#include "proc_control.h"
#include "guest_domain.h"
#include "watch_calls.h"
#include "fd_client.h"
#include "fd_filter.h"
#include "fd_filter_program.h"
#include "descriptor_calls.h"
#include "fs_buffer.h"
#include "adapter_buffer.h"
#include "process_owner.h"
#include "host_policy.h"
#include <signal.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sched.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include "filesystem_ioctl.h"
#include <sys/utsname.h>

extern struct md_export_reply md_guest_export_control(unsigned, uint64_t, unsigned, unsigned, int);
long md_guest_export(int descriptor) {
    struct md_export_reply reply=md_guest_export_control(MD_EXPORT_BEGIN,0,0,0,0);
    /* EVENT_WAIT: observe each uncertain channel slot selected under the table
     * turn. Task death cancels the capture without closing any guest slot. */
    while (reply.endpoint>=0 && !reply.token) {
        uint64_t cookie=0; socklen_t length=sizeof(cookie);
        long flags=RAW3(fcntl,reply.endpoint,F_GETFD,0);
        long error=flags<0 ? flags : RAW5(getsockopt,reply.endpoint,SOL_SOCKET,SO_COOKIE,&cookie,&length);
        reply=md_guest_export_control(MD_EXPORT_OBSERVED,cookie,flags<0 ? 0 : flags,length,error);
    }
    if (reply.endpoint==MD_EXPORT_ADMIT) {
        long installed=RAW1(prctl,MD_GUEST_FD_CHANNEL);
        if (installed<0) return installed;
        /* Cold admission must not turn an invalid application source into the
         * infrastructure endpoint which just occupied that number. */
        if (installed==descriptor) descriptor=-1;
        reply=md_guest_export_control(MD_EXPORT_READY,0,0,0,0);
    }
    if (reply.endpoint<0) return reply.endpoint;
    if (!reply.token) return -EPROTO;
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    struct iovec vector = {&reply.token, sizeof(reply.token)};
    struct msghdr message = {.msg_iov = &vector, .msg_iovlen = 1,
        .msg_control = control.bytes, .msg_controllen = sizeof(control)};
    struct cmsghdr *header = CMSG_FIRSTHDR(&message);
    header->cmsg_level = SOL_SOCKET; header->cmsg_type = SCM_RIGHTS;
    header->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(header), &descriptor, sizeof(descriptor));
    long result = RAW3(sendmsg, reply.endpoint, &message, MSG_NOSIGNAL | MSG_DONTWAIT);
    return result;
}
static const char proc_fds_path[]="self/fd/";
static long md_guest_open_proc_fds(int directory, unsigned long flags) {
    return RAW4(openat,directory,proc_fds_path,flags,0);
}

extern int md_guest_ready(int, const char *, uint64_t, const struct md_interception_abi *);
extern void md_guest_done(void), md_guest_allocate(void), md_guest_allocated(void);
extern char md_guest_ready_checkpoint[],md_guest_done_checkpoint[],md_guest_export_control_checkpoint[];
extern char md_guest_fd_control_checkpoint[],md_guest_buffer_control_checkpoint[],md_guest_task_control_checkpoint[];
extern char md_guest_exported_checkpoint[],md_enter_checkpoint[];
extern void md_guest_dispatch_entry(void);
extern void md_guest_exported(void), md_guest_store(void), md_guest_stored(void);
extern void md_guest_load_byte(void), md_guest_loaded_byte(void);
extern void md_guest_store_ids(void), md_guest_stored_ids(void);
extern void md_guest_load_groups(void), md_guest_loaded_groups(void);
extern void md_guest_store_groups(void), md_guest_stored_groups(void);
extern char md_guest_copy_begin[], md_guest_copy_end[];
extern char md_guest_watch_gate[], md_guest_watch_return[];
extern char md_guest_ipc_wait[];
extern struct md_fd_observation md_guest_fd_control(unsigned, uint64_t, long, long);
extern char md_guest_anchor_return[];
extern long md_guest_buffer_control(unsigned, unsigned long, unsigned long);
extern char md_guest_task_control[], md_guest_task_call[], md_guest_task_return[];
extern char md_guest_task_abort[], md_guest_task_abort_return[];
extern char md_guest_task_collect[], md_guest_task_collected[];
extern char md_guest_task_completed[], md_guest_watch_completed[];
extern char md_guest_buffer_admitted[];
#include "epoll_calls.h"
#include "poll_calls.h"
#include "clone_call.h"
static long dispatch_files(const struct md_fs *fs, long nr, unsigned long a0, unsigned long a1, unsigned long a2,
        unsigned long a3, unsigned long a4, unsigned long a5, int control, unsigned long prepared) {
    unsigned long args[] = {a0, a1, a2, a3, a4, a5};
    if (nr==SYS_clone3) return md_clone3_prepare(a0,a1);
    if (nr==SYS_epoll_ctl || nr==SYS_epoll_pwait || nr==SYS_epoll_pwait2) return md_epoll_call(nr,args);
    if (nr==SYS_ppoll || nr==SYS_pselect6 || nr==SYS_rt_sigsuspend) return md_poll_call(nr,args,prepared);
    if (nr==SYS_seccomp || nr==SYS_prctl) {
        md_cooperative_revoke();
        long result=md_adapter_readonly_install();
        return result ? result : md_fd_filter_prepare_sandbox();
    }
    if (nr==SYS_unshare || nr==SYS_clone) return md_pid_filter_install();
    if (nr==SYS_setns) {
        long result=md_pid_filter_install();
        return result ? result : md_setns(args);
    }
    if (nr==SYS_kill && (int)a0<=0) return md_pid_signal_group((int)a0,(int)a1);
    if (nr==SYS_pidfd_send_signal) return md_pidfd_signal(args);
    if (nr==SYS_ioctl && md_pidfd_namespace_command((unsigned)a1)) return md_pidfd_namespace(args);
    if (nr==SYS_ioctl && md_terminal_identity_command((unsigned)a1))
        return md_pid_terminal(args);
    if (control>=0 && (nr==SYS_read || nr==SYS_write || nr==SYS_readv || nr==SYS_writev
            || nr==SYS_pread64 || nr==SYS_pwrite64 || nr==SYS_preadv || nr==SYS_pwritev
            || nr==SYS_preadv2 || nr==SYS_pwritev2 || nr==SYS_lseek || nr==SYS_ioctl || nr==SYS_splice
            || nr==SYS_ftruncate || nr==SYS_fsync || nr==SYS_fdatasync || nr==SYS_mmap || nr==SYS_getdents64))
        return md_proc_control_call(nr,args,control);
    if (nr==SYS_ioctl && md_terminal_attributes_command((unsigned)a1))
        return md_terminal_attributes(args);
    if (nr==SYS_ioctl && md_filesystem_ioctl_command((unsigned)a1))
        return fs->endpoint[0] ? md_namespace_call(fs,md_executable,nr,args) : RAW3(ioctl,a0,a1,a2);
    if (nr==SYS_uname || nr==SYS_sethostname || nr==SYS_setdomainname) {
        if (nr!=SYS_uname && a1>=sizeof(((struct utsname *)0)->nodename)) return -EINVAL;
        long memory=md_adapter_buffer_take();
        if (memory<0) return memory;
        long result=nr==SYS_uname ? 0 : md_read_memory((void *)memory,(void *)a0,a1);
        if (!result) result=md_guest_buffer_control(MD_BUFFER_UTS,0,0);
        if (!result && nr==SYS_uname) result=md_write_memory((void *)a0,(void *)memory,sizeof(struct utsname));
        long released=md_adapter_buffer_release();
        return result ? result : released;
    }
    if (nr == SYS_shmget || nr == SYS_shmat || nr == SYS_shmdt || nr == SYS_shmctl)
        return md_shm_dispatch(nr,args);
    if (nr == SYS_semget || nr == SYS_semctl || nr == SYS_semop || nr == SYS_semtimedop
            || nr == SYS_msgget || nr == SYS_msgctl || nr == SYS_msgsnd || nr == SYS_msgrcv)
        return md_ipc_dispatch(nr,args);
    if (nr == SYS_read || nr == SYS_readv || nr == SYS_ioctl || nr == SYS_splice
            || nr == SYS_tee || nr == SYS_sendfile) return md_watch_read_local(fs, nr, args);
    if (nr==SYS_dup || nr==SYS_dup3 || nr==SYS_fcntl)
        return md_descriptor_duplicate(fs,nr,args);
    if (nr == SYS_inotify_init1 || nr == SYS_inotify_rm_watch)
        return md_watch_call(fs, nr, args);
    if (nr == SYS_recvmmsg) return md_socket_ancillary_batch(fs, args, prepared);
    switch (nr) {
        case SYS_chroot:
            return md_namespace_call(fs, md_executable, nr, args);
#define CALL(name) case SYS_##name:
        MD_FILE_CALLS(CALL)
            return md_file_call(fs, md_executable, nr, args);
        MD_SOCKET_CALLS(CALL)
            return md_socket_call(fs, md_executable, nr, args,prepared);
#undef CALL
        case SYS_execve: return md_guest_execat(fs, AT_FDCWD, (void *)a0, (void *)a1, (void *)a2, 0, prepared);
        case SYS_execveat: return md_guest_execat(fs, a0, (void *)a1, (void *)a2, (void *)a3, a4, prepared);
        default: return -ENOSYS;
    }
}
/* A syscall's filesystem selection is task-local even with CLONE_VM and
 * independently unshared CLONE_FS. Ordinary launches do not copy md_fs. */
__attribute__((noinline)) static long dispatch_scoped(long nr, unsigned long a0, unsigned long a1,
        unsigned long a2, unsigned long a3, unsigned long a4, unsigned long a5, int control,unsigned long prepared) {
    struct md_fs fs = md_files;
    fs.managed_paths = 1;
    return dispatch_files(&fs, nr, a0, a1, a2, a3, a4, a5, control,prepared);
}
long md_guest_dispatch(long nr, unsigned long a0, unsigned long a1, unsigned long a2,
        unsigned long a3, unsigned long a4, unsigned long a5, unsigned scoped,unsigned long prepared) {
    if (scoped&MD_DISPATCH_DEVICE_EVENTS) {
        unsigned long args[]={a0,a1,a2,a3,a4,a5};
        return nr==SYS_socket ? md_network_open(args) : md_device_event_call(nr,args);
    }
    if (scoped&MD_DISPATCH_NETWORK_DATA) {
        unsigned long args[]={a0,a1,a2,a3,a4,a5};
        if (nr==SYS_recvmmsg) return md_socket_ancillary_batch(&md_files,args,prepared);
        return md_network_data_call(nr,args);
    }
    if (scoped&MD_DISPATCH_NETWORK_CONTROL) {
        unsigned long args[]={a0,a1,a2,a3,a4,a5};
        return md_network_call(nr,args);
    }
    if ((scoped&MD_DISPATCH_NETWORK_SCOPED) && nr==SYS_socket) {
        unsigned long args[]={a0,a1,a2,a3,a4,a5};
        return md_network_open(args);
    }
    if (scoped&MD_DISPATCH_OFFLINE_SOCKET) {
        unsigned long args[]={a0,a1,a2,a3,a4,a5};
        long error=md_socket_offline_error(nr,args);
        if (error) return error;
    }
    if ((scoped&MD_DISPATCH_NETWORK_SCOPED) && nr==SYS_socketpair) {
        struct md_fs fs=md_files; fs.managed_paths=!!(scoped&MD_DISPATCH_PATHS);
        unsigned long args[]={a0,a1,a2,a3,a4,a5};
        return md_socket_pair(&fs,args,1);
    }
    int control=md_dispatch_control_kind(scoped);
    return (scoped&MD_DISPATCH_PATHS) ? dispatch_scoped(nr,a0,a1,a2,a3,a4,a5,control,prepared)
        : dispatch_files(&md_files,nr,a0,a1,a2,a3,a4,a5,control,prepared);
}
#include "cooperative_native.h"
#include "svc_runtime.h"
#include "task_checkpoint.h"
extern char __executable_start[],_etext[];
extern char md_guest_signal_entry[],md_guest_signal_entry_committed[],md_guest_signal_entry_end[];
static struct md_interception_abi abi = {
    .code_begin=(uintptr_t)__executable_start,.code_end=(uintptr_t)_etext,
    .signal_entry=(uintptr_t)md_guest_signal_entry,
    .signal_entry_committed=(uintptr_t)md_guest_signal_entry_committed,
    .signal_entry_end=(uintptr_t)md_guest_signal_entry_end,
    .svc_runtime=(uintptr_t)&md_svc_runtime,
    .native_begin=(uintptr_t)md_cooperative_native,
    .native_call=(uintptr_t)md_cooperative_native_call,
    .native_return=(uintptr_t)md_cooperative_native_return,
    .native_end=(uintptr_t)md_cooperative_native_end,
    .task_control=(uintptr_t)md_guest_task_control_checkpoint, .task_call=(uintptr_t)md_guest_task_call,
    .task_return=(uintptr_t)md_guest_task_return, .task_abort=(uintptr_t)md_guest_task_abort,
    .task_abort_return=(uintptr_t)md_guest_task_abort_return,
    .task_completed=(uintptr_t)md_guest_task_completed,
    .task_collect=(uintptr_t)md_guest_task_collect, .task_collected=(uintptr_t)md_guest_task_collected,
    .buffer_control=(uintptr_t)md_guest_buffer_control_checkpoint,
    .buffer_admit=(uintptr_t)md_guest_buffer_admitted,
    .buffer_result=(uintptr_t)md_guest_buffer_result,
    .buffer_enter=(uintptr_t)md_guest_buffer_admit,
    .fd_control=(uintptr_t)md_guest_fd_control_checkpoint,
    .capture_gate=(uintptr_t)md_guest_anchor_return,
    .ipc_wait = (uintptr_t)md_guest_ipc_wait,
    .magic = MD_INTERCEPTION_MAGIC, .size = sizeof(struct md_interception_abi),
    .ready = (uintptr_t)md_guest_ready_checkpoint, .done = (uintptr_t)md_guest_done_checkpoint,
    .done_entry = (uintptr_t)md_guest_done,
    .exported_entry = (uintptr_t)md_guest_exported,
    .allocate = (uintptr_t)md_guest_allocate, .allocated = (uintptr_t)md_guest_allocated,
    .dispatch = (uintptr_t)md_guest_dispatch_entry, .export_fd = (uintptr_t)md_guest_export,
    .exported = (uintptr_t)md_guest_exported_checkpoint, .export_control = (uintptr_t)md_guest_export_control_checkpoint,
    .proc_open=(uintptr_t)md_guest_open_proc_fds, .proc_open_path=(uintptr_t)proc_fds_path,
    .store = (uintptr_t)md_guest_store, .stored = (uintptr_t)md_guest_stored,
    .load_byte = (uintptr_t)md_guest_load_byte, .loaded_byte = (uintptr_t)md_guest_loaded_byte,
    .store_ids = (uintptr_t)md_guest_store_ids, .stored_ids = (uintptr_t)md_guest_stored_ids,
    .raw_gate = (uintptr_t)md_raw_return,
    .copy_begin = (uintptr_t)md_guest_copy_begin, .copy_end = (uintptr_t)md_guest_copy_end,
    .watch_gate = (uintptr_t)md_guest_watch_gate,
    .watch_return = (uintptr_t)md_guest_watch_return,
    .watch_completed = (uintptr_t)md_guest_watch_completed,
    .load_groups = (uintptr_t)md_guest_load_groups, .loaded_groups = (uintptr_t)md_guest_loaded_groups,
    .store_groups = (uintptr_t)md_guest_store_groups, .stored_groups = (uintptr_t)md_guest_stored_groups,
    .enter = (uintptr_t)md_enter_checkpoint
};

int md_interception_map_image(int fd) {
    long result = RAW3(prctl, MD_GUEST_MAP_IMAGE, fd, 0);
    return result < 0 ? (int)result : 0;
}
void md_interception_enter_image(uintptr_t entry,uintptr_t stack,const void *image,void *aux,unsigned count,int machine) {
    long result=md_enter(entry,stack,image,aux,count,machine);
    md_die("enter guest image",result<0 ? result : -EPROTO);
}

static int host_signal_install(int inherited) {
    struct { struct md_kernel_action action; uint64_t mask; } initial={0};
    /* Signal return and filter installation share immutable kernel inputs.
     * Prepare their owner before guest handlers can run, even without filters. */
    long result=md_adapter_readonly_install();
    if (!result) result=RAW4(rt_sigaction,SIGSYS,0,&initial.action,sizeof(uint64_t));
    if (!result) result=RAW4(rt_sigprocmask,SIG_SETMASK,0,&initial.mask,sizeof(uint64_t));
    if (!result) result=md_guest_buffer_control(MD_BUFFER_SIGNAL_ENABLE,(uintptr_t)&initial,sizeof(initial));
    if (result) return (int)result;
    struct md_kernel_action native={0};
    uint64_t mask=initial.mask&~(UINT64_C(1)<<(SIGSYS-1));
    result=RAW4(rt_sigaction,SIGSYS,&native,0,sizeof(uint64_t));
    if (!result) result=RAW4(rt_sigprocmask,SIG_SETMASK,&mask,0,sizeof(uint64_t));
    if (result) return (int)result;
    /* Exec resets signal dispositions, not the inherited seccomp program. */
    if (inherited) return 0;
    struct md_interception_program code;
    md_signal_program_build(&abi,&code);
    struct sock_fprog program={code.count,code.code};
    return (int)RAW3(seccomp,SECCOMP_SET_MODE_FILTER,0,&program);
}
int md_interception_install(int inherited) {
    abi.host_policy=md_app_uid_active;
    long result = 0;
    int listener = -1, capabilities=0;
    if (!inherited) {
        uintptr_t watch = (uintptr_t)md_guest_watch_return;
        uintptr_t capture = (uintptr_t)md_guest_anchor_return;
        uintptr_t task=(uintptr_t)md_guest_task_return, abort_task=(uintptr_t)md_guest_task_abort_return;
        uintptr_t admit=(uintptr_t)md_guest_buffer_admitted;
        uintptr_t native=(uintptr_t)md_cooperative_native_return;
        const uintptr_t sites[]={native,admit,abort_task,task,watch,capture,
#define SITE(field,symbol) (uintptr_t)symbol+4,
            MD_CHECKPOINT_SITES(SITE)
#undef SITE
        };
        for (unsigned i=0;i<sizeof(sites)/sizeof(*sites);++i)
            if (sites[i]>>32!=(uintptr_t)md_guest_ready>>32) return -EINVAL;
        struct md_interception_program code;
        md_interception_program_build(&abi,&code);
        struct sock_fprog program={code.count,code.code};
        result = RAW5(prctl, PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
        if (!result) {
            result = RAW3(seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_NEW_LISTENER, &program);
            if (result >= 0) { listener = (int)result; result = 0; }
        }
    } else if (RAW1(prctl, PR_GET_SECCOMP) != SECCOMP_MODE_FILTER) result = -EINVAL;
    if (!result) {
        capabilities=md_guest_ready(listener, md_files.endpoint, MD_INTERCEPTION_MAGIC, &abi);
        if (!(capabilities&MD_INTERCEPTION_COOPERATIVE)) md_cooperative_revoke();
    }
    if (!result) md_fd_client_enable();
    if (listener >= 0) RAW1(close, listener);
    if (!result) result=md_adapter_buffer_install();
    if (!result && (capabilities&MD_INTERCEPTION_HOST_POLICY)) result=host_signal_install(inherited);
    return result ? (int)result : capabilities;
}
