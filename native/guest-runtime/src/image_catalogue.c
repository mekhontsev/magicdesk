#define _GNU_SOURCE
#include "image_catalogue.h"
#include "elf_admission.h"
#include "raw.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

struct md_image_catalogue {
    struct md_admission entry;
    int snapshot;
    struct stat physical, logical;
    char object[33];
};
static int same(const struct stat *a, const struct stat *b) {
    return a->st_dev == b->st_dev && a->st_ino == b->st_ino;
}
static int selected(struct md_image_catalogue *c, const struct stat *st) {
    return c && (same(st, &c->physical) || same(st, &c->entry.source_stat));
}
static int original_fd(struct md_image_catalogue *c, int fd) {
    if (!c) return fd;
    struct stat st;
    if (fstat(fd, &st)) return fd;
    if (selected(c, &st)) return c->entry.source;
    const int required = F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL;
    if (st.st_size != c->physical.st_size) return fd;
    int seals = fcntl(fd, F_GET_SEALS);
    if (seals < 0 || (seals & required) != required) return fd;
    /* Independent opens cannot share offsets. Authenticate sealed copies by
     * content, without retaining one service FD for every application open. */
    char a[4096], b[4096];
    for (off_t offset = 0; offset < st.st_size; offset += sizeof(a)) {
        size_t n = st.st_size - offset;
        if (n > sizeof(a)) n = sizeof(a);
        if (pread(fd, a, n, offset) != (ssize_t)n
                || pread(c->snapshot, b, n, offset) != (ssize_t)n || memcmp(a, b, n)) return fd;
    }
    return c->entry.source;
}
static int open_snapshot(struct md_image_catalogue *c, int flags) {
    if (flags & O_DIRECTORY) return -ENOTDIR;
    if (!(flags & O_PATH) && (flags & (O_ACCMODE | O_TRUNC | O_APPEND))) return -EACCES;
    if ((flags & (O_CREAT | O_EXCL)) == (O_CREAT | O_EXCL)) return -EEXIST;
    if (flags & O_PATH) {
        char path[64];
        snprintf(path, sizeof(path), "/proc/self/fd/%d", c->snapshot);
        int fd = open(path, O_PATH | O_CLOEXEC);
        return fd < 0 ? -errno : fd;
    }
    /* A readable proc reopen can be denied by SELinux. Sealed copies preserve
     * independent offsets without relaxing labels or sharing an open description. */
    int source = md_exec_image_dup(c->entry.image);
    if (source < 0) return source;
    struct md_admission copy = {.source = -1};
    int error = md_admission_snapshot(&copy, source);
    int fd = !error ? md_exec_image_dup(copy.image) : error;
    md_admission_close(&copy);
    return fd;
}
int md_image_catalogue_open(struct md_inode_store *s, const char *path, struct md_image_catalogue **out) {
    *out = NULL;
    if (!path) return 0;
    if (*path != '/') return -EINVAL;
    struct md_image_catalogue *c = calloc(1, sizeof(*c));
    if (!c) return -ENOMEM;
    c->entry.source = c->snapshot = -1;
    md_page_size = (size_t)sysconf(_SC_PAGESIZE);
    int fd = md_inode_open_resolved(s, MD_INODE_ROOT, path, O_RDONLY | O_CLOEXEC, 0, 0);
    int error = fd < 0 ? fd : md_inode_object_id(s, fd, c->object);
    if (!error) error = md_inode_fstat(s, fd, &c->logical);
    if (error) { if (fd >= 0) close(fd); md_image_catalogue_close(c); return error; }
    error = md_admission_snapshot(&c->entry, fd);
    if (!error) {
        c->snapshot = md_exec_image_dup(c->entry.image);
        if (c->snapshot < 0) error = c->snapshot;
    }
    if (!error && fstat(c->snapshot, &c->physical)) error = -errno;
    if (error) { md_image_catalogue_close(c); return error; }
    c->logical.st_mode = S_IFREG | 04755;
    c->logical.st_uid = c->logical.st_gid = 0;
    *out = c;
    return 0;
}
void md_image_catalogue_close(struct md_image_catalogue *c) {
    if (!c) return;
    if (c->snapshot >= 0) close(c->snapshot);
    md_admission_close(&c->entry);
    free(c);
}
int md_catalogue_open(struct md_image_catalogue *c, struct md_inode_store *s, int dir,
        const char *path, int flags, mode_t mode, uint64_t resolve) {
    if (c) {
        int fd = md_inode_open_resolved(s, dir, path,
            O_PATH | O_CLOEXEC | (flags & (O_NOFOLLOW | O_DIRECTORY)), 0, resolve);
        if (fd >= 0) {
            struct stat st;
            int error = fstat(fd, &st) ? -errno : 0;
            close(fd);
            if (error) return error;
            if (selected(c, &st)) return open_snapshot(c, flags);
        } else if (fd != -ENOENT) return fd;
    }
    return md_inode_open_resolved(s, dir, path, flags, mode, resolve);
}
int md_catalogue_open_object(struct md_image_catalogue *c, struct md_inode_store *s, const char *id, int flags) {
    if (c && !strcmp(id, c->object)) return open_snapshot(c, flags);
    return md_inode_open_object(s, id, flags);
}
int md_catalogue_open_image(struct md_image_catalogue *c, struct md_inode_store *s, int dir,
        const char *path, int flags, struct md_image_identity *image) {
    int fd = md_inode_open_image(s, dir, path, flags, image);
    if (fd >= 0 && c && !strcmp(image->object, c->object)) {
        close(fd);
        fd = open_snapshot(c, O_RDONLY | O_CLOEXEC);
    }
    return fd;
}
int md_catalogue_object_id(struct md_image_catalogue *c, struct md_inode_store *s, int fd, char out[33]) {
    return md_inode_object_id(s, original_fd(c, fd), out);
}
int md_catalogue_path(struct md_image_catalogue *c, struct md_inode_store *s, int fd, char *out, size_t size) {
    return md_inode_path(s, original_fd(c, fd), out, size);
}
int md_catalogue_fstat(struct md_image_catalogue *c, struct md_inode_store *s, int fd, struct stat *st) {
    int source = original_fd(c, fd);
    int error = md_inode_fstat(s, source, st);
    if (!error && c && source == c->entry.source) {
        nlink_t links = st->st_nlink;
        *st = c->logical; st->st_nlink = links;
    }
    return error;
}
int md_catalogue_stat(struct md_image_catalogue *c, struct md_inode_store *s, int dir,
        const char *path, int flags, struct stat *st) {
    int error = md_inode_stat(s, dir, path, flags, st);
    if (!error && selected(c, st)) {
        nlink_t links = st->st_nlink;
        *st = c->logical; st->st_nlink = links;
    }
    return error;
}
