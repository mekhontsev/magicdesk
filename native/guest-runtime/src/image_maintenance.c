#define _GNU_SOURCE
#include "image_maintenance.h"
#include "image_publish.h"
#include "inode_internal.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <stdio.h>
#include <string.h>
#include <sys/file.h>
#include <sys/syscall.h>
#include <unistd.h>

int md_image_remove(const char *directory, int layer) {
    if (!directory || directory[0] != '/' || strlen(directory) >= PATH_MAX) return -EINVAL;
    char path[PATH_MAX]; strcpy(path, directory);
    char *name = strrchr(path, '/')+1;
    if (!*name || !strcmp(name, ".") || !strcmp(name, "..")) return -EINVAL;
    if (layer && (strncmp(name, "layer-v1-", 9) || strlen(name+9) != 64
            || strspn(name+9, "0123456789abcdef") != 64)) return -EINVAL;
    char tomb[NAME_MAX+1], tomb_path[PATH_MAX];
    int length = snprintf(tomb, sizeof(tomb), ".md-remove-%s", name);
    if (length < 0 || length >= (int)sizeof(tomb)) return -ENAMETOOLONG;
    length = snprintf(tomb_path, sizeof(tomb_path), "%.*s%s", (int)(name-path), path, tomb);
    if (length < 0 || length >= (int)sizeof(tomb_path)) return -ENAMETOOLONG;
    struct stat present;
    int resumed = lstat(directory, &present) && errno == ENOENT;
    const char *selected = resumed ? tomb_path : directory;
    int root = -1, r = 0;
    struct md_inode_store *s = NULL;
    if (!layer && !resumed) { r = md_inode_store_open_exclusive(directory, &s); if (!r) root = s->root; }
    else {
        root = open(selected, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (root < 0) r = -errno;
        if (!r && flock(root, LOCK_EX | LOCK_NB)) r = errno == EWOULDBLOCK ? -EBUSY : -errno;
        struct stat st;
        if (!r && !resumed && (fstatat(root, "objects", &st, AT_SYMLINK_NOFOLLOW) || !S_ISDIR(st.st_mode))) r = -EINVAL;
    }
    int parent = -1;
    if (!r && (parent = openat(root, "..", O_RDONLY | O_DIRECTORY | O_CLOEXEC)) < 0) r = -errno;
    struct stat opened, named;
    if (!r && (fstat(root, &opened) || fstatat(parent, resumed ? tomb : name, &named, AT_SYMLINK_NOFOLLOW)
            || opened.st_dev != named.st_dev || opened.st_ino != named.st_ino)) r = -ESTALE;
    if (!r) {
        if (!resumed && syscall(SYS_renameat2, parent, name, parent, tomb, RENAME_NOREPLACE)) r = -errno;
        if (!r && fsync(parent)) r = -errno;
        if (!r) r = md_image_discard(parent, tomb);
        if (!r && fsync(parent)) r = -errno;
    }
    if (parent >= 0) close(parent);
    if (s) md_inode_store_close(s); else if (root >= 0) close(root);
    return r;
}
