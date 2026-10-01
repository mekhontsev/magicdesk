#define _GNU_SOURCE
#include "guest_domain.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/magic.h>
#include <linux/openat2.h>
#include <linux/pidfd.h>
#include <limits.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

struct md_guest_domain { unsigned refs; int root, cwd, root_process; };
struct md_guest_domain *md_domain_new(void) {
    struct md_guest_domain *d = calloc(1, sizeof(*d));
    if (d) *d = (struct md_guest_domain){.refs = 1, .root = -1, .cwd = -1, .root_process = -1};
    return d;
}
struct md_guest_domain *md_domain_fork(struct md_guest_domain *d, int shared) {
    if (!d) return NULL;
    if (shared) { d->refs++; return d; }
    struct md_guest_domain *copy = md_domain_new();
    if (copy && d->root >= 0) {
        copy->root = fcntl(d->root, F_DUPFD_CLOEXEC, 0);
        copy->root_process = fcntl(d->root_process, F_DUPFD_CLOEXEC, 0);
        if (d->cwd >= 0) copy->cwd = fcntl(d->cwd, F_DUPFD_CLOEXEC, 0);
        if (copy->root < 0 || copy->root_process < 0 || (d->cwd >= 0 && copy->cwd < 0)) {
            md_domain_release(copy); return NULL;
        }
    }
    return copy;
}
void md_domain_release(struct md_guest_domain *d) {
    if (!d || --d->refs) return;
    if (d->root >= 0) close(d->root);
    if (d->cwd >= 0) close(d->cwd);
    if (d->root_process >= 0) close(d->root_process);
    free(d);
}
int md_domain_restricted(const struct md_guest_domain *d) { return d && d->root >= 0; }
int md_domain_restrict(struct md_guest_domain *d, pid_t pid, const char *path) {
    if (!d || d->root >= 0) return -EPERM;
    /* The root contract admits a process-owned proc directory.
     * Other roots fail; no fake success is used for unsupported guest chroot. */
    if (strncmp(path, "/proc/self/", 11)) return -ENOTSUP;
    if (strcmp(path + 11, "fdinfo") && strcmp(path + 11, "fd")) return -ENOTSUP;
    char native[96];
    snprintf(native, sizeof(native), "/proc/%d/%s", pid, path + 11);
    int fd = open(native, O_PATH | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) return -errno;
    struct statfs fs;
    int error = fstatfs(fd, &fs) ? -errno : fs.f_type != PROC_SUPER_MAGIC ? -EXDEV : 0;
    int process = error ? -1 : syscall(SYS_pidfd_open, pid, PIDFD_THREAD);
    if (!error && process < 0 && errno == EINVAL) process = syscall(SYS_pidfd_open, pid, 0);
    if (!error && process < 0) error = -errno;
    if (error) close(fd); else { d->root = fd; d->root_process = process; }
    return error;
}
static int root_alive(const struct md_guest_domain *d) {
    struct pollfd event = {.fd = d->root_process, .events = POLLIN};
    int result;
    do { result = poll(&event, 1, 0); } while (result < 0 && errno == EINTR);
    return result < 0 ? -errno : result ? -ENOENT : 0;
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
    d->cwd = fd; return 0;
}
int md_domain_path_error(const struct md_guest_domain *d) {
    if (!md_domain_restricted(d)) return -EINVAL;
    int error = root_alive(d);
    return error ? error : -EACCES;
}
int md_domain_native_call(long nr, const unsigned long a[6]) {
#define ARGUMENT(name, index, value) if (nr == SYS_##name && a[index] == (unsigned long)(value)) return 1;
    MD_DOMAIN_KERNEL_ARGUMENTS(ARGUMENT)
#undef ARGUMENT
    switch (nr) {
#define NATIVE_CASE(name) case SYS_##name:
    MD_GATE_TRANSPORT_CALLS(NATIVE_CASE)
    MD_DOMAIN_KERNEL_CALLS(NATIVE_CASE)
#undef NATIVE_CASE
    case SYS_lseek: case SYS_getdents64:
    case SYS_getsockname: case SYS_getpeername:
    case SYS_recvfrom: case SYS_sendmmsg:
    case SYS_seccomp: case SYS_getresuid: case SYS_getresgid: return 1;
    case SYS_sendto: return !a[4];
    case SYS_prctl: return a[0] == PR_SET_DUMPABLE || a[0] == PR_SET_SECCOMP
        || a[0] == PR_SET_NO_NEW_PRIVS || a[0] == PR_GET_NO_NEW_PRIVS;
    default: return 0;
    }
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
    return flags == (O_RDONLY | O_DIRECTORY | O_CLOEXEC)
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
