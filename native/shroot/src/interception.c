#define _GNU_SOURCE
#include "bootstrap.h"
#include "raw.h"
#include "interception.h"
#include "interception_program.h"
#include "linux_abi.h"
#include "fd_client.h"
#include "fs_buffer.h"
#include "adapter_buffer.h"
#include "process_owner.h"
#include "host_policy.h"
#include <signal.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/prctl.h>
#include <sys/socket.h>

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
extern void md_guest_exported(void);
#include "guest_transfer.h"
extern char md_guest_copy_begin[], md_guest_copy_end[];
extern char md_guest_watch_gate[], md_guest_watch_return[];
extern char md_guest_native_return[];
extern char md_guest_ipc_wait[];
extern struct md_fd_observation md_guest_fd_control(unsigned, uint64_t, long, long);
extern char md_guest_anchor_return[];
extern long md_guest_buffer_control(unsigned, unsigned long, unsigned long);
extern char md_guest_task_control[], md_guest_task_call[], md_guest_task_return[];
extern char md_guest_task_abort[], md_guest_task_abort_return[];
extern char md_guest_task_collect[], md_guest_task_collected[];
extern char md_guest_task_completed[], md_guest_watch_completed[];
extern char md_guest_compare_exchange[], md_guest_exchanged[];
extern char md_guest_buffer_admitted[];
#include "cooperative_native.h"
#include "svc_runtime.h"
#include "svc_direct.h"
#include "task_checkpoint.h"
extern char __executable_start[],_etext[];
extern char md_guest_signal_entry[],md_guest_signal_entry_committed[],md_guest_signal_entry_end[];
static struct md_interception_abi abi = {
    .code_begin=(uintptr_t)__executable_start,.code_end=(uintptr_t)_etext,
    .signal_entry=(uintptr_t)md_guest_signal_entry,
    .signal_entry_committed=(uintptr_t)md_guest_signal_entry_committed,
    .signal_entry_end=(uintptr_t)md_guest_signal_entry_end,
    .svc_runtime=(uintptr_t)&md_svc_runtime,
    .svc_return=(uintptr_t)md_svc_signal_checkpoint,
    .cooperative_registration=(uintptr_t)&md_cooperative_registration,
    .native_begin=(uintptr_t)md_cooperative_native,
    .native_call=(uintptr_t)md_cooperative_native_call,
    .native_return=(uintptr_t)md_cooperative_native_return,
    .native_end=(uintptr_t)md_cooperative_native_end,
    .task_control=(uintptr_t)md_guest_task_control_checkpoint, .task_call=(uintptr_t)md_guest_task_call,
    .task_return=(uintptr_t)md_guest_task_return, .task_abort=(uintptr_t)md_guest_task_abort,
    .task_abort_return=(uintptr_t)md_guest_task_abort_return,
    .task_completed=(uintptr_t)md_guest_task_completed,
    .compare_exchange=(uintptr_t)md_guest_compare_exchange, .exchanged=(uintptr_t)md_guest_exchanged,
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
    .transfer = (uintptr_t)md_guest_transfer,
    .raw_gate = (uintptr_t)md_raw_return,
    .adapter_native_return=(uintptr_t)md_guest_native_return,
    .copy_begin = (uintptr_t)md_guest_copy_begin, .copy_end = (uintptr_t)md_guest_copy_end,
    .watch_gate = (uintptr_t)md_guest_watch_gate,
    .watch_return = (uintptr_t)md_guest_watch_return,
    .watch_completed = (uintptr_t)md_guest_watch_completed,
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
    long result=md_cooperative_routes_init(&abi);
    if (result) return (int)result;
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
