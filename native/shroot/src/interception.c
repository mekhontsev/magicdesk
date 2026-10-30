#define _GNU_SOURCE
#include "bootstrap.h"
#include "file_calls.h"
#include "namespace.h"
#include "socket_calls.h"
#include "network_calls.h"
#include "socket_ancillary.h"
#include "raw.h"
#include "interception.h"
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
    if (nr==SYS_ppoll || nr==SYS_pselect6) return md_poll_call(nr,args,prepared);
    if (nr==SYS_seccomp || nr==SYS_prctl) {
        md_cooperative_revoke();
        return md_fd_filter_prepare_sandbox();
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
static const struct md_interception_abi abi = {
    .code_begin=(uintptr_t)__executable_start,.code_end=(uintptr_t)_etext,
    .svc_runtime=(uintptr_t)&md_svc_runtime,
    .native_begin=(uintptr_t)md_cooperative_native,
    .native_call=(uintptr_t)md_cooperative_native_call,
    .native_return=(uintptr_t)md_cooperative_native_return,
    .native_end=(uintptr_t)md_cooperative_native_end,
    .task_control=(uintptr_t)md_guest_task_control_checkpoint, .task_call=(uintptr_t)md_guest_task_call,
    .task_return=(uintptr_t)md_guest_task_return, .task_abort=(uintptr_t)md_guest_task_abort,
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
    if (result==1) {
        result=RAW2(prctl,PR_SET_DUMPABLE,0);
        if (!result) result=md_enter(entry,stack,image,NULL,0,0);
    }
    md_die("enter guest image",result<0 ? result : -EPROTO);
}

int md_interception_install(int inherited) {
    long result = 0;
    int listener = -1, capabilities=0;
    if (!inherited) {
        uintptr_t gate = (uintptr_t)md_raw_return;
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
#define COUNT(name) + 1
        enum { transport_instructions = 2 * (1 MD_GATE_TRANSPORT_CALLS(COUNT)),
            native_instructions = 2 * (0 MD_ENTRY_NATIVE_CALLS(COUNT)) };
#undef COUNT
#define TRACE(n) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_##n, 0, 1), \
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_DISPATCH),
#define OBSERVE(n) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_##n, 0, 1), \
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_OBSERVE),
#define NATIVE(n) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_##n, 0, 1), \
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
#define DESCRIPTOR(n) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_##n, 0, 1), \
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_FD),
        struct sock_filter filter[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
            /* Fixed-number service markers cannot alias these calls. Linux
             * can cache these decisions without reading IP or arguments;
             * later descriptor selectors and application filters still apply. */
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            MD_DOMAIN_WAIT_CALLS(NATIVE)
            /* A blocking epoll wait can acquire virtual interests after it
             * starts. Admit its owner before sleeping, but leave zero-time
             * native probes and owned task/adapter calls in the kernel. */
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_epoll_pwait2,1,0),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_epoll_pwait,0,14),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)(gate>>32),0,6),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)admit,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_USER_NOTIF),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)task,1,0),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)gate,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_epoll_pwait2,3,0),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[3])),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,0,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_READINESS),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            MD_DOMAIN_EXECUTION_CALLS(NATIVE)
            /* All service gates live in the fixed bootstrap text image.
             * Ordinary guest code skips this table with one high-IP check. */
#define COUNT_MARKER(field,symbol) +2
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)((uintptr_t)md_guest_ready>>32),0,
                25+native_instructions MD_CHECKPOINT_SITES(COUNT_MARKER)),
#undef COUNT_MARKER
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
#define MARKER(field,symbol) \
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)((uintptr_t)symbol+4),0,1), \
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_CHECKPOINT),
            MD_CHECKPOINT_SITES(MARKER)
#undef MARKER
            /* An admitted native effect retains its table turn and journal.
             * Application filters are still evaluated by the kernel. */
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)native,0,1+native_instructions),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            MD_ENTRY_NATIVE_CALLS(NATIVE)
            /* Descriptor admission has no dependency on application prctl
             * policy. Its exact site still runs every application filter. */
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)admit,0,3),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_gettid,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_USER_NOTIF),
            /* Exact private-helper termination site. Application KILL_PROCESS
             * still has precedence; ERRNO on ordinary exit cannot strand its
             * private file table. Ordinary guest exits never use this site. */
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)abort_task,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_THREAD),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)task,0,4),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_gettid,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_USER_NOTIF),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_TASK),
            /* Declared on the original listener; a later listenerless filter
             * cannot acquire or inherit USER_NOTIF delivery. */
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)watch, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)capture, 0, 3),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_openat, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_CAPTURE),
            /* Descriptor metadata must remain serviceable after an application
             * installs a higher-precedence filter or becomes nondumpable. */
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_prctl, 0, 10),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, MD_GUEST_FD_CHANNEL, 6, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, MD_GUEST_BUFFER, 5, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, MD_GUEST_PROC_IMAGE, 4, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, MD_GUEST_PROC_FILE, 3, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, MD_GUEST_SHM, 2, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, MD_GUEST_IPC, 1, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, MD_GUEST_MAP_IMAGE, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            DESCRIPTOR(clone) DESCRIPTOR(clone3) OBSERVE(chroot)
            MD_FD_CLOSE_FILTER,
            MD_DOMAIN_FD_CALLS(DESCRIPTOR)
            /* Descriptor commands use the kernel's unsigned-int argument. */
#define FD_ARGUMENT(n, index, value) \
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_##n, 0, 4), \
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[index])), \
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, value, 0, 1), \
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_FD), \
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            MD_DOMAIN_FD_ARGUMENTS(FD_ARGUMENT)
#undef FD_ARGUMENT
            NATIVE(read) NATIVE(write) NATIVE(readv) NATIVE(writev)
            NATIVE(pread64) NATIVE(pwrite64)
            /* Internal mappings, admitted regular-file cursors and descriptor
             * capacity use native resources, not a second guest operation. */
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)(gate>>32),0,15),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)gate,0,13),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            NATIVE(mmap) NATIVE(munmap) NATIVE(mremap) NATIVE(mprotect)
            NATIVE(prlimit64) NATIVE(lseek)
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            /* File mappings require their descriptor's live mount policy.
             * Anonymous non-replacing mappings and ordinary data IO stay native. */
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_mmap,0,6),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[3])),
            BPF_JUMP(BPF_JMP|BPF_JSET|BPF_K,MAP_FIXED,2,0),
            BPF_JUMP(BPF_JMP|BPF_JSET|BPF_K,MAP_ANONYMOUS,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_MEMORY),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_munmap,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_MEMORY),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_mremap,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_MEMORY),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_mprotect,0,4),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[2])),
            BPF_JUMP(BPF_JMP|BPF_JSET|BPF_K,PROT_EXEC,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_MEMORY),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
            NATIVE(getpid) NATIVE(getppid) NATIVE(gettid)
            NATIVE(eventfd2) NATIVE(timerfd_create) NATIVE(timerfd_settime) NATIVE(timerfd_gettime)
            NATIVE(exit) NATIVE(exit_group) NATIVE(set_tid_address)
            /* Only guest-owned limits need adaptation; all other limits keep
             * their native behavior and application seccomp still takes precedence. */
#define LIMIT(n, index) \
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_##n,0,5), \
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[index])), \
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,RLIMIT_NPROC,1,0), \
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,RLIMIT_NOFILE,0,1), \
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_IDENTITY), \
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            LIMIT(getrlimit,0) LIMIT(setrlimit,0) LIMIT(prlimit64,1)
#undef LIMIT
            MD_DOMAIN_KERNEL_CONTROL_CALLS(NATIVE)
            /* Compare the full policy argument, not only its low kernel int. */
#define NATIVE_ARGUMENT(n, index, value) \
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_##n, 0, 6), \
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[index]) + 4), \
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 0, 0, 3), \
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[index])), \
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, value, 0, 1), \
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW), \
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            MD_DOMAIN_KERNEL_ARGUMENTS(NATIVE_ARGUMENT)
#undef NATIVE_ARGUMENT
            /* No guest key service is installed. Report absence consistently,
             * without entering Android's keyrings or fabricating successful
             * isolation. Application ERRNO/TRAP/KILL filters still apply. */
#define UNAVAILABLE(n) BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_##n,0,1), \
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|ENOSYS),
            UNAVAILABLE(add_key) UNAVAILABLE(request_key) UNAVAILABLE(keyctl)
            /* Mounting a namespace view does not enable POSIX queue IO. Never
             * leak unadapted mq calls into the Android IPC namespace. */
            UNAVAILABLE(mq_open) UNAVAILABLE(mq_unlink) UNAVAILABLE(mq_timedsend)
            UNAVAILABLE(mq_timedreceive) UNAVAILABLE(mq_notify) UNAVAILABLE(mq_getsetattr)
#undef UNAVAILABLE
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer) + 4),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)(gate >> 32), 0, 22 + transport_instructions),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)gate, 0, 20 + transport_instructions),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            /* Already unconditional kernel operations in every domain. The
             * original application call still enters its adapter, and every
             * application filter still evaluates these internal syscalls. */
            MD_GATE_TRANSPORT_CALLS(NATIVE)
            /* These internal queries already request native metadata/paths,
             * never logical guest metadata. Application filters stay in force. */
            NATIVE(fstat) NATIVE(readlinkat)
            /* Runtime transport validates its native peer identity; guest
             * credential queries use their original SVC, never this gate. */
            NATIVE(getuid) NATIVE(geteuid) NATIVE(getgid) NATIVE(getegid)
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_chdir, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_CWD),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_fchdir, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_CWD),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_execve, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_EXEC),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_execveat, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_EXEC),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_NATIVE),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            TRACE(uname) TRACE(sethostname) TRACE(setdomainname)
#define IDENTITY(n) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_##n, 0, 1), \
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_IDENTITY),
            IDENTITY(getuid) IDENTITY(geteuid) IDENTITY(getgid) IDENTITY(getegid)
            IDENTITY(getresuid) IDENTITY(getresgid) IDENTITY(setresuid) IDENTITY(setresgid)
            IDENTITY(setuid) IDENTITY(setgid) IDENTITY(setreuid) IDENTITY(setregid)
            IDENTITY(setfsuid) IDENTITY(setfsgid) IDENTITY(setgroups) IDENTITY(getgroups)
            IDENTITY(capget) IDENTITY(capset)
            TRACE(shmget) TRACE(shmat) TRACE(shmdt) TRACE(shmctl)
            TRACE(semget) TRACE(semctl) TRACE(semop) TRACE(semtimedop)
            TRACE(msgget) TRACE(msgctl) TRACE(msgsnd) TRACE(msgrcv)
#undef IDENTITY
            OBSERVE(unshare) OBSERVE(setns)
            OBSERVE(seccomp)
#define NOTIFY(n) BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_##n,0,1), \
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_USER_NOTIF),
            MD_FS_NOTIFICATION_CALLS(NOTIFY)
#undef NOTIFY
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_prctl, 0, 5),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, PR_SET_SECCOMP, 1, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, PR_SET_DUMPABLE, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_OBSERVE),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            MD_FILE_TASK_CALLS(TRACE)
            TRACE(inotify_init1) TRACE(inotify_rm_watch)
            TRACE(recvmmsg)
            MD_SOCKET_CALLS(TRACE)
            TRACE(execve) TRACE(execveat)
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_NATIVE)
        };
#undef TRACE
#undef OBSERVE
#undef NATIVE
#undef DESCRIPTOR
        struct sock_fprog program = {sizeof(filter) / sizeof(filter[0]), filter};
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
    return result ? (int)result : capabilities;
}
