#define _GNU_SOURCE
#include "fs_mount_internal.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/random.h>
#include <unistd.h>

int md_view_same(const struct stat *a, const struct stat *b) {
    return a->st_dev == b->st_dev && a->st_ino == b->st_ino;
}
int md_view_fdpath(int fd, char *out, size_t capacity) {
    char path[64]; snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    ssize_t n = readlink(path, out, capacity);
    if (n < 0) return -errno;
    if ((size_t)n >= capacity) return -ENAMETOOLONG;
    out[n] = 0; return 0;
}
static int beneath(const char *path, const char *parent) {
    size_t n = strlen(parent);
    if (n == 1 && parent[0] == '/') return path[0] == '/';
    return !strncmp(path, parent, n) && (!path[n] || path[n] == '/');
}
static unsigned bucket(const struct stat *st) {
    return (unsigned)(st->st_ino ^ (st->st_dev * 2654435761U)) % MD_VIEW_BUCKETS;
}
static struct md_view_object *find(struct md_fs_mounts *m, const struct stat *st) {
    for (struct md_view_object *p = m->table[bucket(st)]; p; p = p->next)
        if (md_view_same(&p->identity, st)) return p;
    return NULL;
}
int md_view_record(struct md_filesystem *fs, int fd, int mount, struct md_view_object **out) {
    struct stat st;
    if (fstat(fd, &st)) return -errno;
    struct md_fs_mounts *m = fs->mounts;
    struct md_view_object *p = find(m, &st);
    if (p) {
        /* One native object cannot silently acquire conflicting mount policy. */
        if (p->mount != mount) return -EXDEV;
        *out = p; return 0;
    }
    if (m->objects == MD_VIEW_OBJECT_LIMIT) return -ENFILE;
    p = calloc(1, sizeof(*p));
    if (!p) return -ENOMEM;
    char proc[64]; snprintf(proc, sizeof(proc), "/proc/self/fd/%d", fd);
    p->fd = open(proc, O_PATH | O_CLOEXEC);
    if (p->fd < 0) { int r = -errno; free(p); return r; }
    unsigned char bytes[16];
    if (getrandom(bytes, sizeof(bytes), 0) != sizeof(bytes)) {
        int r = errno ? -errno : -EIO; close(p->fd); free(p); return r;
    }
    for (unsigned i = 0; i < sizeof(bytes); ++i) {
        p->id[2*i] = "0123456789abcdef"[bytes[i] >> 4];
        p->id[2*i+1] = "0123456789abcdef"[bytes[i] & 15];
    }
    p->identity = st; p->mount = mount;
    unsigned b = bucket(&st); p->next = m->table[b]; m->table[b] = p; ++m->objects;
    *out = p; return 0;
}
int md_view_identity(struct md_filesystem *fs, int fd, struct md_view_object **out) {
    *out = NULL;
    if (fd < 0) return -EXDEV;
    struct stat st;
    if (fstat(fd, &st)) return -errno;
    struct md_view_object *p = find(fs->mounts, &st);
    if (p) { *out = p; return 0; }
    /* Admit inherited/native descriptors only when their current dentry is in
     * an explicitly attached tree. Registered identities survive unlink/rename. */
    char path[PATH_MAX], root[PATH_MAX];
    int r = md_view_fdpath(fd, path, sizeof(path));
    if (r) return r;
    for (unsigned i = 0; i < fs->mounts->count; ++i) {
        r = md_view_fdpath(fs->mounts->mounts[i].source, root, sizeof(root));
        if (r) return r;
        if (beneath(path, root)) return md_view_record(fs, fd, (int)i, out);
    }
    return -EXDEV;
}
int md_view_path(struct md_filesystem *fs, int fd, int mount, char *out, size_t size) {
    if (mount < 0) return md_inode_path(fs->store, fd, out, size);
    struct md_view_mount *m = &fs->mounts->mounts[mount];
    char root[PATH_MAX], path[PATH_MAX], target[PATH_MAX];
    struct stat st;
    if (fstat(fd, &st)) return -errno;
    if (!st.st_nlink) return -ENOENT;
    int r = md_view_fdpath(m->source, root, sizeof(root));
    if (!r) r = md_view_fdpath(fd, path, sizeof(path));
    if (!r && !beneath(path, root)) r = -EXDEV;
    if (!r) r = md_inode_path(fs->store, m->target, target, sizeof(target));
    if (!r && snprintf(out, size, "%s%s", target, path+strlen(root)) >= (int)size) r = -ERANGE;
    return r;
}
void md_fs_mounts_close(struct md_filesystem *fs) {
    md_fs_mount_identity_close(fs);
    struct md_fs_mounts *m = fs->mounts;
    if (!m) return;
    for (unsigned i = 0; i < MD_VIEW_BUCKETS; ++i) {
        struct md_view_object *p = m->table[i];
        while (p) { struct md_view_object *next = p->next; close(p->fd); free(p); p = next; }
    }
    for (unsigned i = 0; i < m->count; ++i) {
        if (m->mounts[i].source >= 0) close(m->mounts[i].source);
        if (m->mounts[i].target >= 0) close(m->mounts[i].target);
    }
    free(m); fs->mounts = NULL;
}
int md_fs_mounts_open(struct md_filesystem *fs, const struct md_fs_attachment *entries, unsigned count) {
    if (fs->mounts || count > MD_FS_MOUNTS_MAX) return -EINVAL;
    if (!count) return 0;
    fs->mounts = calloc(1, sizeof(*fs->mounts));
    if (!fs->mounts) return -ENOMEM;
    int r = 0;
    for (unsigned i = 0; i < count && !r; ++i) {
        const struct md_fs_attachment *entry = &entries[i];
        struct md_view_mount *m = &fs->mounts->mounts[fs->mounts->count++];
        m->source = m->target = -1; m->readonly = entry->readonly;
        if (!entry->source || !entry->target || entry->source[0] != '/' || entry->target[0] != '/') { r = -EINVAL; break; }
        m->source = open(entry->source, O_PATH | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        if (m->source < 0) { r = -errno; break; }
        m->target = md_inode_open(fs->store, MD_INODE_ROOT, entry->target, O_PATH | O_DIRECTORY | O_CLOEXEC, 0);
        if (m->target < 0) { r = m->target; break; }
        if (fstat(m->source, &m->native) || fstat(m->target, &m->guest)) { r = -errno; break; }
        char source[PATH_MAX], target[PATH_MAX];
        r = md_view_fdpath(m->source, source, sizeof(source));
        if (!r) r = md_inode_path(fs->store, m->target, target, sizeof(target));
        if (!r && (!strcmp(target, "/") || beneath(target, "/dev") || beneath(target, "/proc") || beneath(target, "/sys"))) r = -EINVAL;
        for (unsigned j = 0; j < i && !r; ++j) {
            char other[PATH_MAX];
            r = md_view_fdpath(fs->mounts->mounts[j].source, other, sizeof(other));
            if (!r && (beneath(source, other) || beneath(other, source))) r = -EXDEV;
            if (!r) r = md_inode_path(fs->store, fs->mounts->mounts[j].target, other, sizeof(other));
            if (!r && (beneath(target, other) || beneath(other, target))) r = -EXDEV;
        }
    }
    if (r) md_fs_mounts_close(fs);
    return r;
}
