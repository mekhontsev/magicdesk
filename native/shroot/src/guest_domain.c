#define _GNU_SOURCE
#include "guest_domain.h"
#include "mount_namespace_internal.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/magic.h>
#include <linux/netlink.h>
#include <linux/openat2.h>
#include "task_lifetime.h"
#include <limits.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/statfs.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

enum { ROOT_SET=1, CWD_SET=2 };
struct md_guest_domain {
    atomic_uint refs;
    unsigned owners;
    pthread_mutex_t lock;
    struct md_resource_queue order;
    atomic_int path_state;
    struct md_fs_location guest_root;
    struct md_fs_anchor *cwd_origin;
    struct md_mount_namespace *mounts;
    struct md_guest_domain *previous, *next;
    int root, cwd;
    struct md_task_lifetime root_task;
};
struct md_guest_domain *md_domain_new(void) {
    struct md_guest_domain *d = calloc(1, sizeof(*d));
    if (d) {
        atomic_init(&d->refs, 1); atomic_init(&d->path_state, 0);
        d->owners = 1; d->root = d->cwd = -1;
        d->root_task.fd=-1;
        if (pthread_mutex_init(&d->lock, NULL)) { free(d); return NULL; }
        if (md_resource_queue_init(&d->order)) { pthread_mutex_destroy(&d->lock); free(d); return NULL; }
    }
    return d;
}
static void attach(struct md_guest_domain *d, struct md_mount_namespace *view) {
    d->mounts=md_mount_namespace_retain(view);
    d->next=view->domains;
    if (d->next) d->next->previous=d;
    view->domains=d;
}
void md_domain_bind_mounts(struct md_guest_domain *d, struct md_mount_namespace *view) {
    if (d->mounts==view) return;
    struct md_mount_namespace *old=d->mounts;
    if (old) {
        pthread_mutex_lock(&old->domains_lock);
        if (d->previous) d->previous->next=d->next;
        else old->domains=d->next;
        if (d->next) d->next->previous=d->previous;
        d->mounts=NULL; d->previous=d->next=NULL;
        pthread_mutex_unlock(&old->domains_lock);
    }
    if (view) {
        pthread_mutex_lock(&view->domains_lock);
        attach(d,view);
        pthread_mutex_unlock(&view->domains_lock);
    }
    md_mount_namespace_release(old);
}
struct md_guest_domain *md_domain_fork(struct md_guest_domain *d, int shared) {
    if (!d) return NULL;
    if (shared) { d->owners++; md_domain_pin(d); return d; }
    struct md_guest_domain *copy = md_domain_new();
    if (!copy) return NULL;
    struct md_mount_namespace *view=d->mounts;
    if (view) pthread_mutex_lock(&view->domains_lock);
    pthread_mutex_lock(&d->lock);
    copy->guest_root = d->guest_root;
    copy->cwd_origin=md_fs_anchor_retain(d->cwd_origin);
    atomic_store(&copy->path_state, atomic_load(&d->path_state));
    if (copy && d->root >= 0) {
        copy->root = fcntl(d->root, F_DUPFD_CLOEXEC, 0);
        int lifetime=md_task_lifetime_copy(&copy->root_task,&d->root_task);
        if (d->cwd >= 0) copy->cwd = fcntl(d->cwd, F_DUPFD_CLOEXEC, 0);
        if (copy->root < 0 || lifetime || (d->cwd >= 0 && copy->cwd < 0)) {
            pthread_mutex_unlock(&d->lock);
            if (view) pthread_mutex_unlock(&view->domains_lock);
            md_domain_release(copy); return NULL;
        }
    }
    pthread_mutex_unlock(&d->lock);
    if (view) { attach(copy,view); pthread_mutex_unlock(&view->domains_lock); }
    return copy;
}
void md_domain_release(struct md_guest_domain *d) {
    if (!d) return;
    --d->owners; md_domain_unpin(d);
}
void md_domain_pin(struct md_guest_domain *d) {
    if (d) atomic_fetch_add_explicit(&d->refs, 1, memory_order_relaxed);
}
void md_domain_unpin(struct md_guest_domain *d) {
    if (!d || atomic_fetch_sub_explicit(&d->refs, 1, memory_order_acq_rel) != 1) return;
    md_domain_bind_mounts(d,NULL);
    if (d->root >= 0) close(d->root);
    if (d->cwd >= 0) close(d->cwd);
    md_task_lifetime_close(&d->root_task);
    md_fs_anchor_release(d->cwd_origin);
    md_resource_queue_destroy(&d->order);
    pthread_mutex_destroy(&d->lock); free(d);
}
struct md_guest_domain *md_domain_unshare_prepare(struct md_guest_domain *d) {
    return md_domain_fork(d, d && d->owners == 1);
}
int md_domain_shared(const struct md_guest_domain *d) { return d && d->owners!=1; }
int md_fs_order_begin(struct md_guest_domain *d, struct md_fs_order *op, struct md_resource_wakeup *wake) {
    if (!d || !op) return -EINVAL;
    if (op->fs) return -EBUSY;
    md_domain_pin(d); op->fs=d;
    int ready=md_resource_order_begin(&d->order,&op->order,wake);
    if (ready<0) { md_domain_unpin(op->fs); op->fs=NULL; }
    return ready;
}
enum md_resource_order_state md_fs_order_state(const struct md_fs_order *op) {
    return md_resource_order_state(op ? &op->order : NULL);
}
int md_fs_order_start(struct md_fs_order *op) {
    return md_resource_order_start(op ? &op->order : NULL);
}
static int order_retired(struct md_fs_order *op, int result) {
    if (op && !op->order.queue) { md_domain_unpin(op->fs); op->fs=NULL; }
    return result;
}
int md_fs_order_cancel(struct md_fs_order *op) {
    return order_retired(op,md_resource_order_cancel(op ? &op->order : NULL));
}
int md_fs_order_complete(struct md_fs_order *op) {
    return order_retired(op,md_resource_order_complete(op ? &op->order : NULL));
}
int md_domain_scoped(const struct md_guest_domain *d) {
    return d && (atomic_load_explicit(&d->path_state, memory_order_acquire) & ROOT_SET);
}
void md_domain_paths(struct md_guest_domain *d, struct md_fs_location *out, struct md_fs_anchor **cwd) {
    *out = (struct md_fs_location){0};
    *cwd=NULL;
    if (!d || !atomic_load_explicit(&d->path_state,memory_order_acquire)) return;
    pthread_mutex_lock(&d->lock);
    *out = d->guest_root;
    *cwd=md_fs_anchor_retain(d->cwd_origin);
    pthread_mutex_unlock(&d->lock);
}
int md_domain_set_cwd(struct md_guest_domain *d, struct md_fs_anchor *cwd) {
    if (!d) return -ESRCH;
    struct md_fs_anchor *old=NULL;
    pthread_mutex_lock(&d->lock);
    int error=d->root>=0 && cwd ? -EPERM : 0;
    if (!error) {
        old=d->cwd_origin; d->cwd_origin=md_fs_anchor_retain(cwd);
        int state=atomic_load_explicit(&d->path_state,memory_order_relaxed) & ~CWD_SET;
        atomic_store_explicit(&d->path_state,state | (cwd ? CWD_SET : 0),memory_order_release);
    }
    pthread_mutex_unlock(&d->lock);
    md_fs_anchor_release(old); return error;
}
int md_domain_set_root(struct md_guest_domain *d, const struct md_fs_location *root) {
    if (!d) return -ESRCH;
    pthread_mutex_lock(&d->lock);
    int error = d->root >= 0 ? -EPERM : 0;
    if (!error) {
        d->guest_root = *root;
        int state=atomic_load_explicit(&d->path_state,memory_order_relaxed) & ~ROOT_SET;
        atomic_store_explicit(&d->path_state,state | ((root->object[0] || root->mount) ? ROOT_SET : 0), memory_order_release);
    }
    pthread_mutex_unlock(&d->lock); return error;
}
int md_domain_restricted(const struct md_guest_domain *d) { return d && d->root >= 0; }
void md_domain_relocate_root(struct md_guest_domain *d, const struct md_fs_location *old,
        const struct md_fs_location *next, const struct md_fs_anchor *old_cwd,
        struct md_fs_anchor *next_cwd) {
    if (!d) return;
    struct md_fs_anchor *retired=NULL;
    pthread_mutex_lock(&d->lock);
    if (d->root<0) {
        int state=atomic_load_explicit(&d->path_state,memory_order_relaxed);
        if (d->guest_root.mount==old->mount && !strcmp(d->guest_root.object,old->object)) {
            d->guest_root=*next; state|=ROOT_SET;
        }
        if (md_fs_anchor_same(d->cwd_origin,old_cwd)) {
            retired=d->cwd_origin; d->cwd_origin=md_fs_anchor_retain(next_cwd); state|=CWD_SET;
        }
        atomic_store_explicit(&d->path_state,state,memory_order_release);
    }
    pthread_mutex_unlock(&d->lock);
    md_fs_anchor_release(retired);
}
void md_domain_relocate_roots(struct md_mount_namespace *view, const struct md_fs_location *old,
        const struct md_fs_location *next, const struct md_fs_anchor *old_cwd,
        struct md_fs_anchor *next_cwd) {
    if (!view) return;
    pthread_mutex_lock(&view->domains_lock);
    for (struct md_guest_domain *d=view->domains;d;d=d->next)
        md_domain_relocate_root(d,old,next,old_cwd,next_cwd);
    pthread_mutex_unlock(&view->domains_lock);
}
int md_domain_mount_root_used(struct md_mount_namespace *view, unsigned mount) {
    if (!view || !mount) return 0;
    int used=0;
    pthread_mutex_lock(&view->domains_lock);
    for (struct md_guest_domain *d=view->domains;d && !used;d=d->next) {
        pthread_mutex_lock(&d->lock);
        used=d->root<0 && (d->guest_root.mount==mount || (mount==view->base.id
            && !d->guest_root.mount && d->guest_root.object[0]));
        pthread_mutex_unlock(&d->lock);
    }
    pthread_mutex_unlock(&view->domains_lock);
    return used;
}
const char *md_domain_proc_root_name(const char *path) {
    if (strncmp(path,"/proc/self/",11)) return NULL;
    const char *name=path+11, *tail;
    const char *result;
    if (!strncmp(name,"fdinfo",6)) { tail=name+6; result="fdinfo"; }
    else if (!strncmp(name,"fd",2)) { tail=name+2; result="fd"; }
    else return NULL;
    if (*tail && *tail!='/') return NULL;
    for (;;) {
        while (*tail=='/') ++tail;
        if (*tail=='.' && (!tail[1] || tail[1]=='/')) ++tail;
        else break;
    }
    return *tail ? NULL : result;
}
int md_domain_restrict(struct md_guest_domain *d, pid_t pid, const char *path) {
    if (!d || d->root >= 0 || md_domain_scoped(d)) return -EPERM;
    /* The root contract admits a process-owned proc directory.
     * Other roots fail; no fake success is used for unsupported guest chroot. */
    const char *name=md_domain_proc_root_name(path);
    if (!name) return -ENOTSUP;
    char native[96];
    snprintf(native, sizeof(native), "/proc/%d/%s", pid, name);
    int fd = open(native, O_PATH | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) return -errno;
    struct statfs fs;
    int error = fstatfs(fd, &fs) ? -errno : fs.f_type != PROC_SUPER_MAGIC ? -EXDEV : 0;
    struct md_task_lifetime task={.fd=-1};
    if (!error) error=md_task_lifetime_open(&task,pid);
    if (!error) {
        pthread_mutex_lock(&d->lock);
        if (d->root >= 0 || md_domain_scoped(d)) error = -EPERM;
        else { d->root = fd; d->root_task = task; }
        pthread_mutex_unlock(&d->lock);
    }
    if (error) { close(fd); md_task_lifetime_close(&task); }
    return error;
}
static int root_alive(const struct md_guest_domain *d) {
    return md_task_lifetime_alive(&d->root_task);
}
int md_domain_chdir_root(struct md_guest_domain *d) {
    if (!md_domain_restricted(d)) return -EINVAL;
    int error = root_alive(d);
    if (error) return error;
    /* Root is already the admitted guest path. Reopening it in the external
     * supervisor would incorrectly apply its procfs access to a protected task. */
    int fd = fcntl(d->root, F_DUPFD_CLOEXEC, 0);
    if (fd < 0) return -errno;
    if (d->cwd >= 0) close(d->cwd);
    d->cwd = fd; return md_domain_set_cwd(d,NULL);
}
int md_domain_path_error(const struct md_guest_domain *d) {
    if (!md_domain_restricted(d)) return -EINVAL;
    int error = root_alive(d);
    return error ? error : -EACCES;
}
int md_domain_socket_error(int family, int protocol) {
    /* There is no guest audit subsystem. Do not connect guest account/security
     * records to Android's global audit service, even under an actual root UID.
     * Report protocol absence, never successful logging or a translated denial. */
    return family == AF_NETLINK && protocol == NETLINK_AUDIT ? -EPROTONOSUPPORT : 0;
}
int md_domain_native_call(long nr, const unsigned long a[6]) {
#define FD_ARGUMENT(name, index, value) if (nr == SYS_##name && (unsigned)a[index] == (unsigned)(value)) return 1;
    MD_DOMAIN_FD_ARGUMENTS(FD_ARGUMENT)
#undef FD_ARGUMENT
#define ARGUMENT(name, index, value) if (nr == SYS_##name && a[index] == (unsigned long)(value)) return 1;
    MD_DOMAIN_KERNEL_ARGUMENTS(ARGUMENT)
#undef ARGUMENT
    switch (nr) {
#define NATIVE_CASE(name) case SYS_##name:
    MD_GATE_TRANSPORT_CALLS(NATIVE_CASE)
    MD_DOMAIN_KERNEL_CALLS(NATIVE_CASE)
    MD_DOMAIN_FD_CALLS(NATIVE_CASE)
#undef NATIVE_CASE
    case SYS_lseek: case SYS_getdents64: case SYS_socketpair: case SYS_pipe2:
    case SYS_mmap: case SYS_munmap: case SYS_mremap: case SYS_mprotect:
    /* Keep the original wait stop for the guest-debugger adapter. Restricted
     * filesystem roots do not remove the caller's kernel child relationship. */
    case SYS_wait4: case SYS_waitid:
    case SYS_getsockname: case SYS_getpeername:
    case SYS_recvfrom: case SYS_sendmmsg:
    case SYS_seccomp: case SYS_getresuid: case SYS_getresgid: return 1;
    case SYS_sendto: return !a[4];
    case SYS_prctl: return a[0] == PR_SET_DUMPABLE || a[0] == PR_GET_DUMPABLE || a[0] == PR_SET_SECCOMP
        || a[0] == PR_SET_NO_NEW_PRIVS || a[0] == PR_GET_NO_NEW_PRIVS;
    default: return 0;
    }
}
int md_domain_ipc_call(long nr) {
    return nr==SYS_sendmsg || nr==SYS_recvmsg || nr==SYS_sendmmsg
        || nr==SYS_recvmmsg || nr==SYS_socketpair;
}
static int caller_group(pid_t caller) {
    char status[64], line[256]; int group = 0;
    snprintf(status, sizeof(status), "/proc/%d/status", caller);
    FILE *file = fopen(status, "re");
    if (!file) return -errno;
    while (fgets(line, sizeof(line), file)) if (sscanf(line, "Tgid: %d", &group) == 1) break;
    fclose(file);
    return group > 0 ? group : -ESRCH;
}
int md_domain_open_retained(int directory, pid_t caller, const char *path, int flags) {
    if (!path[0]) return -ENOENT;
    if (path[0] == '/') return -EACCES;
    /* Read-only capability exercise. Mutations and magic-link traversal need a
     * complete resource policy, not an implicit escape to the host namespace. */
    if ((flags & O_ACCMODE) != O_RDONLY || (flags & (O_CREAT | O_TRUNC | __O_TMPFILE))) return -ENOTSUP;
    struct stat held, proc;
    if (fstat(directory, &held)) return -errno;
    if (!S_ISDIR(held.st_mode)) return -ENOTDIR;
    char translated[4096];
    if (!stat("/proc", &proc) && held.st_dev == proc.st_dev && held.st_ino == proc.st_ino
            && (!strncmp(path, "self/", 5) || !strcmp(path, "self"))) {
        /* procfs self is caller-relative, not broker-relative. Tgid comes from
         * the kernel status of this owned tracee, never from guest metadata. */
        int group = caller_group(caller);
        if (group < 0) return group;
        int length = snprintf(translated, sizeof(translated), "%d%s", group, path + 4);
        if (length < 0 || length >= (int)sizeof(translated)) return -ENAMETOOLONG;
        path = translated;
    }
    struct open_how how = {.flags = (unsigned)flags | O_CLOEXEC,
        .resolve = RESOLVE_BENEATH | RESOLVE_NO_MAGICLINKS};
    int fd = syscall(SYS_openat2, directory, path, &how, sizeof(how));
    return fd < 0 ? -errno : fd;
}
int md_domain_proc_fd_query(int directory, const char *path, int flags) {
    struct stat held, proc;
    const int allowed = O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK
        | O_LARGEFILE | O_NOCTTY | O_PATH;
    return !(flags & ~allowed)
        && (!strcmp(path, "self/fd") || !strcmp(path, "self/fd/"))
        && !fstat(directory, &held) && !stat("/proc", &proc)
        && held.st_dev == proc.st_dev && held.st_ino == proc.st_ino;
}
int md_domain_proc_fd_number(int directory, pid_t caller, const char *path) {
    if (!path[0]) return -1;
    unsigned long number = 0;
    for (const char *p = path; *p; p++) {
        if (*p < '0' || *p > '9' || number > (INT_MAX - (*p - '0')) / 10UL) return -1;
        number = number * 10 + (*p - '0');
    }
    char native[64];
    int group = caller_group(caller);
    if (group < 0) return -1;
    snprintf(native, sizeof(native), "/proc/%d/fd", group);
    struct stat held, actual;
    if (fstat(directory, &held) || stat(native, &actual)
            || held.st_dev != actual.st_dev || held.st_ino != actual.st_ino) return -1;
    return (int)number;
}
