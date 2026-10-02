#define _GNU_SOURCE
#include "image_publish.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/random.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

/* Only private staging trees and exclusively owned removal tombstones. */
int md_image_discard(int parent, const char *name) {
    int ref = openat(parent, name, O_PATH | O_NOFOLLOW | O_CLOEXEC);
    if (ref < 0) return errno == ENOENT ? 0 : -errno;
    struct stat st;
    if (fstat(ref, &st)) { int r = -errno; close(ref); return r; }
    if (!S_ISDIR(st.st_mode)) { close(ref); return unlinkat(parent, name, 0) ? -errno : 0; }
    char proc[64];
    snprintf(proc, sizeof(proc), "/proc/self/fd/%d", ref);
    /* Imported directories can have mode 000. The retained O_PATH descriptor
     * names our private object, not an archive-controlled symlink target. */
    if (chmod(proc, 0700)) { int r = -errno; close(ref); return r; }
    int fd = openat(ref, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    close(ref);
    if (fd < 0) return -errno;
    DIR *dir = fdopendir(fd);
    if (!dir) { int r = -errno; close(fd); return r; }
    struct dirent *entry;
    int r = 0;
    while (!r) {
        errno = 0;
        entry = readdir(dir);
        if (!entry) { if (errno) r = -errno; break; }
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        r = md_image_discard(fd, entry->d_name);
    }
    closedir(dir);
    if (!r && unlinkat(parent, name, AT_REMOVEDIR)) r = -errno;
    return r;
}
void md_image_publish_close(struct md_image_publish *p) {
    if (p->stage >= 0) { close(p->stage); md_image_discard(p->parent, p->temporary); }
    if (p->parent >= 0) close(p->parent);
    p->stage = p->parent = -1;
}
int md_image_publish_begin(const char *destination, struct md_image_publish *p) {
    memset(p, 0, sizeof(*p)); p->stage = p->parent = -1;
    if (!destination || destination[0] != '/' || strlen(destination) >= PATH_MAX) return -EINVAL;
    char directory[PATH_MAX]; strcpy(directory, destination);
    char *name = strrchr(directory, '/');
    if (!name[1] || !strcmp(name+1, ".") || !strcmp(name+1, "..") || strlen(name+1) > NAME_MAX) return -EINVAL;
    strcpy(p->name, name+1);
    if (name == directory) name[1] = 0; else *name = 0;
    p->parent = open(directory, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (p->parent < 0) return -errno;
    struct stat st;
    int r = 0;
    if (!fstatat(p->parent, p->name, &st, AT_SYMLINK_NOFOLLOW)) r = -EEXIST;
    else if (errno != ENOENT) r = -errno;
    unsigned char random[16];
    if (!r && getrandom(random, sizeof(random), 0) != sizeof(random)) r = errno ? -errno : -EIO;
    if (!r) {
        strcpy(p->temporary, ".md-image-");
        for (unsigned i = 0; i < sizeof(random); ++i) {
            p->temporary[10+i*2] = "0123456789abcdef"[random[i] >> 4];
            p->temporary[11+i*2] = "0123456789abcdef"[random[i] & 15];
        }
        if (mkdirat(p->parent, p->temporary, 0700)) r = -errno;
        if (!r && (p->stage = openat(p->parent, p->temporary, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)) < 0) {
            r = -errno; unlinkat(p->parent, p->temporary, AT_REMOVEDIR);
        }
    }
    if (!r) {
        char proc[64]; snprintf(proc, sizeof(proc), "/proc/self/fd/%d", p->stage);
        if (!realpath(proc, directory)) r = -errno;
        else if (snprintf(p->path, sizeof(p->path), "%s/store", directory) >= (int)sizeof(p->path)) r = -ENAMETOOLONG;
    }
    if (r) md_image_publish_close(p);
    return r;
}
int md_image_publish_commit(struct md_image_publish *p) {
    if (fsync(p->stage)) return -errno;
    if (syscall(SYS_renameat2, p->stage, "store", p->parent, p->name, RENAME_NOREPLACE)) return -errno;
    /* A post-rename sync error is an uncertain durable outcome, not permission
     * to remove or repeat publication. The destination stays for inspection. */
    return fsync(p->parent) ? -errno : 0;
}
