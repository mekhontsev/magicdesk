#define _GNU_SOURCE
#include "image_pool.h"
#include "image_io.h"
#include "image_publish.h"
#include <archive.h>
#include <archive_entry.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

void md_image_pool_name(uint64_t entry, char name[33]) {
    snprintf(name, 33, "%032llx", (unsigned long long)entry);
}
void md_image_pool_close(struct md_image_pool *p) {
    if (p->objects >= 0) close(p->objects);
    if (p->root >= 0) close(p->root);
    p->objects = p->root = -1;
}
static int open_pool(const char *path, struct md_image_pool *p) {
    p->root = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (p->root < 0) return -errno;
    int r = flock(p->root, LOCK_SH | LOCK_NB) ? (errno == EWOULDBLOCK ? -EBUSY : -errno) : 0;
    struct stat st, named;
    if (!r && (fstat(p->root, &st) || lstat(path, &named) || !st.st_nlink
            || st.st_dev != named.st_dev || st.st_ino != named.st_ino)) r = -ESTALE;
    if (!r && (p->objects = openat(p->root, "objects", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW)) < 0) r = -errno;
    if (r) md_image_pool_close(p);
    return r;
}
static int extract(int fd, int objects) {
    if (lseek(fd, 0, SEEK_SET) < 0) return -errno;
    struct archive *ar = archive_read_new();
    if (!ar) return -ENOMEM;
    archive_read_support_format_tar(ar);
    int r = archive_read_open_fd(ar, fd, 65536) == ARCHIVE_OK ? 0 : -EBADMSG;
    uint64_t bytes = 0, index = 0;
    struct archive_entry *entry = NULL;
    while (!r) {
        int rc = archive_read_next_header(ar, &entry);
        if (rc == ARCHIVE_EOF) break;
        if (rc != ARCHIVE_OK || archive_entry_is_encrypted(entry)) { r = -EBADMSG; break; }
        if (++index > 200000) { r = -EFBIG; break; }
        if (archive_entry_filetype(entry) != S_IFREG || archive_entry_hardlink(entry)) continue;
        int64_t size = archive_entry_size(entry);
        if (size < 0 || (uint64_t)size > 8ULL*1024*1024*1024-bytes) { r = -EFBIG; break; }
        char name[33]; md_image_pool_name(index, name);
        int out = openat(objects, name, O_CREAT | O_EXCL | O_WRONLY | O_CLOEXEC | O_NOFOLLOW, 0600);
        if (out < 0) { r = -errno; break; }
        unsigned char buffer[65536];
        uint64_t total = 0;
        while (!r) {
            la_ssize_t n = archive_read_data(ar, buffer, sizeof(buffer));
            if (n < 0) { r = -EBADMSG; break; }
            if (!n) break;
            if ((uint64_t)n > (uint64_t)size-total) { r = -EBADMSG; break; }
            r = md_image_write(out, buffer, (size_t)n); total += (uint64_t)n;
        }
        if (!r && total != (uint64_t)size) r = -EBADMSG;
        struct timespec times[2] = {{archive_entry_mtime(entry), archive_entry_mtime_nsec(entry)},
            {archive_entry_mtime(entry), archive_entry_mtime_nsec(entry)}};
        if (!r && (fchmod(out, 0600 | ((archive_entry_perm(entry) & 0111) ? 0100 : 0))
                || futimens(out, times) || fsync(out))) r = -errno;
        if (close(out) && !r) r = -errno;
        bytes += total;
    }
    if (archive_read_close(ar) != ARCHIVE_OK && !r) r = -EBADMSG;
    archive_read_free(ar);
    return r;
}
int md_image_pool_open(const char *directory, const char *diff_id, int fd, struct md_image_pool *pool) {
    *pool = (struct md_image_pool){.root=-1, .objects=-1};
    if (!md_image_digest_valid(diff_id)) return -EINVAL;
    char path[PATH_MAX];
    if (snprintf(path, sizeof(path), "%s/layer-v1-%s", directory, diff_id+7) >= (int)sizeof(path)) return -ENAMETOOLONG;
    int r = open_pool(path, pool);
    if (r != -ENOENT) return r;
    struct md_image_publish p = {.parent=-1, .stage=-1};
    r = md_image_publish_begin(path, &p);
    if (r == -EEXIST) return open_pool(path, pool);
    int root = -1, objects = -1;
    if (!r && mkdir(p.path, 0700)) r = -errno;
    if (!r && (root = open(p.path, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW)) < 0) r = -errno;
    if (!r && mkdirat(root, "objects", 0700)) r = -errno;
    if (!r && (objects = openat(root, "objects", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW)) < 0) r = -errno;
    if (!r) r = extract(fd, objects);
    if (!r && (fsync(objects) || fsync(root))) r = -errno;
    if (objects >= 0) close(objects);
    if (root >= 0) close(root);
    if (!r) r = md_image_publish_commit(&p);
    md_image_publish_close(&p);
    /* Concurrent builders publish identical provenance, not interchangeable
     * inode identities. Adopt the winner before recording any namespace link. */
    if (!r || r == -EEXIST) r = open_pool(path, pool);
    return r;
}
