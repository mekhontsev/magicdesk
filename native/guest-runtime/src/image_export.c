#define _GNU_SOURCE
#include "image_export.h"
#include "image_io.h"
#include "image_publish.h"
#include "inode_store.h"
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>

struct budget { size_t bytes, entries; };

/* Bounded data export, not a second guest namespace or an executable rootfs. */
static int tree(struct md_inode_store *s, int source, int target, unsigned depth, struct budget *budget) {
    if (depth > 12) return -E2BIG;
    char entries[8192];
    for (;;) {
        ssize_t n = md_inode_getdents(s, source, entries, sizeof(entries));
        if (n <= 0) return (int)n;
        for (size_t at = 0; at < (size_t)n;) {
            struct md_inode_dirent *entry = (void *)(entries + at);
            if (!entry->size || entry->size > (size_t)n - at) return -EIO;
            at += entry->size;
            if (!strcmp(entry->name, ".") || !strcmp(entry->name, "..")) continue;
            if (!budget->entries--) return -E2BIG;
            struct stat st;
            int r = md_inode_stat(s, source, entry->name, AT_SYMLINK_NOFOLLOW, &st);
            if (r) return r;
            if (S_ISLNK(st.st_mode)) continue;
            if (!S_ISDIR(st.st_mode) && !S_ISREG(st.st_mode)) return -EINVAL;
            int input = md_inode_open(s, source, entry->name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC
                    | (S_ISDIR(st.st_mode) ? O_DIRECTORY : 0), 0);
            if (input < 0) return input;
            if (S_ISDIR(st.st_mode)) {
                if (mkdirat(target, entry->name, 0700)) r = -errno;
                int child = !r ? openat(target, entry->name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC) : -1;
                if (!r && child < 0) r = -errno;
                if (!r) r = tree(s, input, child, depth + 1, budget);
                if (child >= 0) close(child);
            } else {
                int output = openat(target, entry->name, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
                if (output < 0) r = -errno;
                char data[65536];
                while (!r) {
                    ssize_t count = read(input, data, sizeof(data));
                    if (count < 0 && errno == EINTR) continue;
                    if (count < 0) { r = -errno; break; }
                    if (!count) break;
                    if ((size_t)count > budget->bytes) { r = -E2BIG; break; }
                    budget->bytes -= (size_t)count;
                    r = md_image_write(output, data, (size_t)count);
                }
                if (output >= 0) close(output);
            }
            close(input);
            if (r) return r;
        }
    }
}

int md_image_export_tree(const char *store, const char *path, const char *destination) {
    if (*path != '/') return -EINVAL;
    struct md_inode_store *s = NULL;
    int r = md_inode_store_open(store, 0, &s);
    int input = !r ? md_inode_open(s, -1, path, O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0) : -1;
    if (!r && input < 0) r = input;
    struct md_image_publish publish = {.parent=-1,.stage=-1};
    if (!r) r = md_image_publish_begin(destination, &publish);
    if (!r && mkdirat(publish.stage, "store", 0700)) r = -errno;
    int target = !r ? openat(publish.stage, "store", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC) : -1;
    if (!r && target < 0) r = -errno;
    struct budget budget = {.bytes=32*1024*1024,.entries=4096};
    if (!r) r = tree(s, input, target, 0, &budget);
    if (target >= 0) close(target);
    if (!r) r = md_image_publish_commit(&publish);
    md_image_publish_close(&publish);
    if (input >= 0) close(input);
    md_inode_store_close(s);
    return r;
}
