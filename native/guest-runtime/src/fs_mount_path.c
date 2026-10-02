#define _GNU_SOURCE
#include "fs_mount_internal.h"
#include "inode_internal.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/openat2.h>
#include <string.h>
#include <unistd.h>

void md_view_location_close(struct md_view_location *p) {
    if (p->parent >= 0) close(p->parent);
    if (p->fd >= 0) close(p->fd);
    p->parent = p->fd = -1;
}
static int root(struct md_filesystem *fs) {
    return md_inode_open(fs->store, MD_INODE_ROOT, "/", O_PATH | O_DIRECTORY | O_CLOEXEC, 0);
}
static int duplicate(int fd) { int copy = fcntl(fd, F_DUPFD_CLOEXEC, 3); return copy < 0 ? -errno : copy; }
static int enter(struct md_filesystem *fs, int *fd, int *mount, uint64_t resolve) {
    if (*mount >= 0) return 0;
    struct stat st;
    if (fstat(*fd, &st)) return -errno;
    for (unsigned i = 0; i < fs->mounts->count; ++i) if (md_view_same(&st, &fs->mounts->mounts[i].guest)) {
        if (resolve & RESOLVE_NO_XDEV) return -EXDEV;
        int copy = duplicate(fs->mounts->mounts[i].source);
        if (copy < 0) return copy;
        close(*fd); *fd = copy; *mount = (int)i; break;
    }
    return 0;
}
int md_view_walk(struct md_filesystem *fs, int base, const char *path, int follow, int missing,
        uint64_t resolve, struct md_view_location *out) {
    *out = (struct md_view_location){.parent=-1,.fd=-1,.mount=-1};
    if (!path || !*path) return -ENOENT;
    if (resolve & ~(RESOLVE_BENEATH | RESOLVE_IN_ROOT | RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS | RESOLVE_NO_XDEV | RESOLVE_CACHED)) return -EINVAL;
    if ((resolve & (RESOLVE_BENEATH | RESOLVE_IN_ROOT)) == (RESOLVE_BENEATH | RESOLVE_IN_ROOT)) return -EINVAL;
    if (resolve & RESOLVE_CACHED) return -EAGAIN;
    if (path[0] == '/' && (resolve & RESOLVE_BENEATH)) return -EXDEV;
    if (strlen(path) >= PATH_MAX) return -ENAMETOOLONG;
    char todo[PATH_MAX]; strcpy(todo, path);
    int fd = base == MD_INODE_ROOT || (path[0] == '/' && !(resolve & RESOLVE_IN_ROOT)) ? root(fs) : duplicate(base);
    if (fd < 0) return fd;
    struct md_view_object *object = NULL;
    int r = md_view_identity(fs, fd, &object), mount = r ? -1 : object->mount;
    if (r == -EXDEV) r = 0;
    if (!r) r = enter(fs, &fd, &mount, resolve);
    int boundary_fd = !r ? duplicate(fd) : -1, boundary_mount = mount;
    if (!r && boundary_fd < 0) r = boundary_fd;
    struct stat boundary;
    if (!r && fstat(boundary_fd, &boundary)) r = -errno;
    unsigned links = 0;
    while (!r) {
        struct stat current;
        if (fstat(fd, &current)) { r = -errno; break; }
        if (!S_ISDIR(current.st_mode)) { r = -ENOTDIR; break; }
        char *start = todo; while (*start == '/') ++start;
        if (!*start) { out->parent = duplicate(fd); out->fd = fd; fd = -1; out->mount = mount; strcpy(out->name, "."); r = out->parent < 0 ? out->parent : 0; break; }
        char *end = start; while (*end && *end != '/') ++end;
        size_t length = (size_t)(end-start);
        if (length > NAME_MAX) { r = -ENAMETOOLONG; break; }
        char name[NAME_MAX+1]; memcpy(name, start, length); name[length] = 0;
        int trailing = *end == '/';
        memmove(todo, end, strlen(end)+1);
        char *rest = todo; while (*rest == '/') ++rest;
        int last = !*rest, next_mount = mount, next = -1;
        if (!strcmp(name, "..") && (resolve & (RESOLVE_BENEATH | RESOLVE_IN_ROOT)) && mount == boundary_mount && md_view_same(&current, &boundary)) {
            if (resolve & RESOLVE_BENEATH) { r = -EXDEV; break; }
            next = duplicate(fd);
        } else if (!strcmp(name, "..") && mount >= 0 && md_view_same(&current, &fs->mounts->mounts[mount].native)) {
            if (resolve & RESOLVE_NO_XDEV) { r = -EXDEV; break; }
            next = md_inode_open(fs->store, fs->mounts->mounts[mount].target, "..", O_PATH | O_DIRECTORY | O_CLOEXEC, 0);
            next_mount = -1;
        } else if (mount < 0) {
            /* The component walker must retain the unconsumed suffix when a
             * logical path crosses into a task-owned host filesystem. */
            struct md_inode_boundary *boundary=fs->store->boundary;
            const char *previous_suffix=boundary ? boundary->suffix : NULL;
            if (boundary) boundary->suffix=todo;
            next = md_inode_open(fs->store, fd, name, O_PATH | O_NOFOLLOW | O_CLOEXEC, 0);
            if (boundary) boundary->suffix=previous_suffix;
            if (next==-EREMOTE && (resolve&(RESOLVE_NO_XDEV|RESOLVE_IN_ROOT|RESOLVE_BENEATH))) next=-EXDEV;
            if (next==-EREMOTE && (resolve&(RESOLVE_NO_SYMLINKS|RESOLVE_NO_MAGICLINKS))) next=-ENOTSUP;
        }
        else { next = openat(fd, name, O_PATH | O_NOFOLLOW | O_CLOEXEC); if (next < 0) next = -errno; }
        if (next == -ENOENT && last && missing && (!trailing || missing > 1)) {
            out->parent = fd; fd = -1; out->mount = mount; strcpy(out->name, name); break;
        }
        if (next < 0) { r = next; break; }
        struct stat st;
        if (fstat(next, &st)) { r = -errno; close(next); break; }
        if (S_ISLNK(st.st_mode) && (!last || follow > 0 || (trailing && follow == 0))) {
            if (++links > 40 || (resolve & RESOLVE_NO_SYMLINKS)) { close(next); r = -ELOOP; break; }
            char target[PATH_MAX];
            ssize_t n = readlinkat(next, "", target, sizeof(target)); close(next);
            if (n < 0) { r = -errno; break; }
            size_t rest_size = strlen(todo);
            if (!n) { r = -ENOENT; break; }
            if ((size_t)n + rest_size >= sizeof(target)) { r = -ENAMETOOLONG; break; }
            memcpy(target+n, todo, rest_size+1); strcpy(todo, target);
            if (target[0] == '/') {
                if (resolve & RESOLVE_BENEATH) { r = -EXDEV; break; }
                close(fd);
                fd = resolve & RESOLVE_IN_ROOT ? duplicate(boundary_fd) : root(fs);
                mount = resolve & RESOLVE_IN_ROOT ? boundary_mount : -1;
                if (fd < 0) r = fd;
            }
            continue;
        }
        r = enter(fs, &next, &next_mount, resolve);
        if (!r && (!last || trailing) && !S_ISDIR(st.st_mode)) r = -ENOTDIR;
        if (r) { close(next); break; }
        if (last) {
            out->boundary = next_mount != mount;
            /* A mount root is materialized by its source FD, never its covered dentry. */
            if (out->boundary || !strcmp(name, "..")) {
                close(fd); fd = duplicate(next); strcpy(name, ".");
                if (fd < 0) { r = fd; close(next); break; }
            }
            out->parent = fd; fd = -1; out->fd = next; out->mount = next_mount; strcpy(out->name, name); break;
        }
        close(fd); fd = next; mount = next_mount;
    }
    if (fd >= 0) close(fd);
    if (boundary_fd >= 0) close(boundary_fd);
    if (r) md_view_location_close(out);
    return r;
}
