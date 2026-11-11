#ifndef MD_SYSCALL_CATALOG_H
#define MD_SYSCALL_CATALOG_H
#include "linux_abi.h"
/* Shared by kernel interception, cooperative routing and task dispatch. */
/* Optional native facilities: only an inherited host TRAP becomes ENOSYS.
 * Permitted calls remain native; guest seccomp always takes precedence. */
#define MD_HOST_OPTIONAL_CALLS(X) \
    X(rseq) \
    X(get_mempolicy) X(set_mempolicy) X(mbind) \
    X(adjtimex) X(clock_adjtime) X(kcmp)
/* No guest provider: these operations must not reach Android's resources. */
#define MD_UNAVAILABLE_CALLS(X) \
    X(syslog) \
    X(add_key) X(request_key) X(keyctl) \
    X(name_to_handle_at) X(open_by_handle_at) \
    X(quotactl) X(quotactl_fd) \
    X(statmount) X(listmount) \
    X(io_uring_setup) X(io_uring_enter) X(io_uring_register) \
    X(mq_open) X(mq_unlink) X(mq_timedsend) X(mq_timedreceive) X(mq_notify) X(mq_getsetattr)
#define MD_FILE_TASK_CALLS(X) \
    X(pipe2) X(openat2) X(lseek) \
    X(fsync) X(fdatasync) X(syncfs) \
    X(mkdirat) X(unlinkat) X(symlinkat) X(renameat) X(renameat2) X(linkat) \
    X(chdir) X(fchdir) X(getcwd) X(fchmodat) X(fchmodat2) X(fchownat) X(utimensat) \
    X(truncate) X(mknodat) X(inotify_add_watch) X(mount) X(umount2) X(pivot_root) \
    X(fsconfig) X(fsmount) X(fspick) X(open_tree) X(open_tree_attr) X(move_mount) X(mount_setattr) X(bpf) \
    X(landlock_create_ruleset) X(landlock_add_rule) X(landlock_restrict_self) \
    X(setxattr) X(lsetxattr) X(removexattr) X(lremovexattr) \
    X(fsetxattr) X(fremovexattr) X(fchmod) X(fchown) \
    X(setxattrat) X(getxattrat) X(listxattrat) X(removexattrat)
#define MD_FILE_CALLS(X) MD_FS_NOTIFICATION_CALLS(X) MD_FILE_TASK_CALLS(X)
#define MD_SOCKET_CALLS(X) X(bind) X(connect) X(sendto) X(sendmsg) X(sendmmsg) \
    X(getsockname) X(getpeername) X(accept) X(accept4) X(recvfrom) X(recvmsg) \
    X(listen) X(socketpair) X(getsockopt)
/* Read-only operations without a cursor or native effect. These also define
 * which interrupted notification calls may reenter their original SVC. */
#define MD_FS_METADATA_CALLS(X) \
    X(newfstatat) X(fstat) X(statx) X(readlinkat) X(faccessat) X(faccessat2) \
    X(statfs) X(fstatfs) X(getxattr) X(lgetxattr) X(fgetxattr) \
    X(listxattr) X(llistxattr) X(flistxattr)
#define MD_FS_NOTIFICATION_CALLS(X) \
    MD_FS_METADATA_CALLS(X) X(openat) X(fsopen) X(getdents64)
#define MD_ENTRY_WORKER_CALLS(X) \
    MD_FS_METADATA_CALLS(X) X(getdents64) \
    X(mkdirat) X(unlinkat) X(renameat) X(renameat2) X(linkat) X(symlinkat) \
    X(fchmodat) X(fchmodat2) X(fchownat) X(fchmod) X(fchown) X(utimensat) \
    X(setxattr) X(lsetxattr) X(fsetxattr) X(removexattr) X(lremovexattr) X(fremovexattr)
#endif
