#define _GNU_SOURCE
#include "launch_registry.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/random.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <linux/fs.h>
#include <unistd.h>

static int valid(const char *id) {
    return id && strlen(id) == 32 && strspn(id, "0123456789abcdef") == 32;
}
static int directory(const char *store, int create) {
    int root = open(store, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (root < 0) return -errno;
    int r = 0;
    if (create && mkdirat(root, "launches", 0700) && errno != EEXIST) r = -errno;
    int fd = r ? -1 : openat(root, "launches", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (!r && fd < 0) r = -errno;
    close(root); return r ? r : fd;
}
int md_launch_register(struct md_launch_registration *owner, const char *store,
        const char *program, const char *cwd, uint32_t guest_uid) {
    owner->directory = owner->record = -1;
    if (strlen(program) >= 4096 || strlen(cwd) >= 4096) return -ENAMETOOLONG;
    int fd = directory(store, 1);
    if (fd < 0) return fd;
    owner->directory = fd;
    unsigned char random[16];
    if (getrandom(random, sizeof(random), 0) != sizeof(random)) { md_launch_unregister(owner); return -EIO; }
    for (unsigned i = 0; i < sizeof(random); ++i) {
        owner->id[i*2] = "0123456789abcdef"[random[i] >> 4];
        owner->id[i*2+1] = "0123456789abcdef"[random[i] & 15];
    }
    owner->id[32] = 0;
    char temporary[40]; snprintf(temporary, sizeof(temporary), ".%s", owner->id);
    owner->record = openat(fd, temporary, O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    int r = owner->record < 0 ? -errno : 0;
    if (!r && flock(owner->record, LOCK_EX | LOCK_NB)) r = -errno;
    struct md_launch_info info = {.version=1, .executor_uid=getuid(), .guest_uid=guest_uid,
        .pid=getpid(), .descriptor=owner->record};
    strcpy(info.program, program); strcpy(info.cwd, cwd);
    const char *label = getenv("MAGICDESK_GUEST_LABEL");
    if (label && strlen(label) < sizeof(info.label)) strcpy(info.label, label);
    if (!r && write(owner->record, &info, sizeof(info)) != sizeof(info)) r = -EIO;
    if (!r && syscall(SYS_renameat2, fd, temporary, fd, owner->id, RENAME_NOREPLACE)) r = -errno;
    if (r) {
        if (owner->record >= 0) { unlinkat(fd, temporary, 0); close(owner->record); owner->record = -1; }
        md_launch_unregister(owner);
    }
    return r;
}
void md_launch_unregister(struct md_launch_registration *owner) {
    if (owner->record >= 0) {
        unlinkat(owner->directory, owner->id, 0);
        close(owner->record); owner->record = -1;
    }
    if (owner->directory >= 0) { close(owner->directory); owner->directory = -1; }
}

/* The locked record is only discovery. pidfd pins the process, and its retained
 * descriptor must name this exact record before any signal can be delivered. */
static int acquire(int dir, const char *id, struct md_launch_info *info) {
    int fd = openat(dir, id, O_RDWR | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return -errno;
    struct stat record, live;
    int r = fstat(fd, &record) ? -errno : 0;
    if (!r && (!S_ISREG(record.st_mode) || record.st_uid != getuid())) r = -EACCES;
    if (!r && !flock(fd, LOCK_EX | LOCK_NB)) {
        // No live owner retains this record. Stale discovery is never a PID kill request.
        struct stat named;
        if (!fstatat(dir, id, &named, AT_SYMLINK_NOFOLLOW)
                && record.st_dev == named.st_dev && record.st_ino == named.st_ino) unlinkat(dir, id, 0);
        r = -ESRCH;
    } else if (!r && errno != EWOULDBLOCK) r = -errno;
    if (!r && (record.st_size != sizeof(*info) || pread(fd, info, sizeof(*info), 0) != sizeof(*info))) r = -EIO;
    if (!r && (info->version != 1 || info->executor_uid != getuid() || info->pid <= 0 || info->descriptor < 3
            || !memchr(info->program, 0, sizeof(info->program)) || !memchr(info->cwd, 0, sizeof(info->cwd))
            || !memchr(info->label, 0, sizeof(info->label)))) r = -EIO;
    int process = -1;
    if (!r && (process = syscall(SYS_pidfd_open, info->pid, 0)) < 0) r = -errno;
    char path[96];
    if (!r) {
        snprintf(path, sizeof(path), "/proc/%d/fd/%d", info->pid, info->descriptor);
        if (stat(path, &live)) r = errno == ENOENT ? -ESRCH : -errno;
        else if (record.st_dev != live.st_dev || record.st_ino != live.st_ino) r = -ESRCH;
    }
    close(fd);
    if (r && process >= 0) close(process);
    return r ? r : process;
}
int md_launch_list(const char *store, md_launch_visitor visit, void *context) {
    int fd = directory(store, 0);
    if (fd == -ENOENT) return 0;
    if (fd < 0) return fd;
    DIR *dir = fdopendir(fd);
    if (!dir) { int r = -errno; close(fd); return r; }
    int r = 0; unsigned count = 0;
    for (;;) {
        errno = 0;
        struct dirent *entry = readdir(dir);
        if (!entry) { if (errno) r = -errno; break; }
        if (entry->d_name[0] == '.') continue;
        if (!valid(entry->d_name) || ++count > 4096) { r = -E2BIG; break; }
        struct md_launch_info info;
        int process = acquire(fd, entry->d_name, &info);
        if (process == -ESRCH || process == -ENOENT) continue;
        if (process < 0) { r = process; break; }
        r = visit(entry->d_name, &info, context);
        close(process);
        if (r) break;
    }
    closedir(dir); return r;
}
int md_launch_stop(const char *store, const char *id) {
    if (!valid(id)) return -EINVAL;
    int dir = directory(store, 0);
    if (dir < 0) return dir;
    struct md_launch_info info;
    int process = acquire(dir, id, &info);
    close(dir);
    if (process < 0) return process;
    int r = syscall(SYS_pidfd_send_signal, process, SIGTERM, NULL, 0) ? -errno : 0;
    close(process); return r;
}
