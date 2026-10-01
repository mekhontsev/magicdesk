#define _GNU_SOURCE
#include "inode_store.h"
#include "inode_watch.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/mman.h>
#include <sys/xattr.h>
#include <unistd.h>

static struct md_inode_store *store(const char *base, const char *name) {
    char path[4096]; assert(snprintf(path, sizeof(path), "%s/%s", base, name) < (int)sizeof(path));
    struct md_inode_store *s = NULL; assert(md_inode_store_open(path, 1, &s) == 0); return s;
}
static int open_file(struct md_inode_store *s, const char *path, int flags) {
    int fd = md_inode_open(s, MD_INODE_ROOT, path, flags, 0600);
    if (fd < 0) fprintf(stderr, "open %s flags=%x errno=%d\n", path, flags, -fd);
    assert(fd >= 0); return fd;
}
static void value(struct md_inode_store *s, const char *path, const char *expected) {
    int fd = open_file(s, path, O_RDONLY);
    char data[32] = {0}; assert(read(fd, data, sizeof(data)-1) == (ssize_t)strlen(expected));
    assert(!strcmp(data, expected)); close(fd);
}
int main(int argc, char **argv) {
    assert(argc == 2 && argv[1][0] == '/'); assert(!mkdir(argv[1], 0700));
    struct md_inode_store *base = store(argv[1], "base"), *a = store(argv[1], "a"), *b = store(argv[1], "b");
    assert(!md_inode_mkdir(base, MD_INODE_ROOT, "/data", 0700));
    int fd = open_file(base, "/data/file", O_CREAT | O_RDWR);
    assert(write(fd, "lower", 5) == 5); close(fd);
    assert(!md_inode_link(base, MD_INODE_ROOT, "/data/file", MD_INODE_ROOT, "/data/alias", 0));
    assert(!md_inode_symlink(base, "/data/file", MD_INODE_ROOT, "/link"));
    assert(!md_inode_store_seal(base));
    assert(md_inode_open(base, MD_INODE_ROOT, "/data/file", O_RDWR, 0) == -EROFS);
    int result = md_inode_snapshot(base, a);
    if (result) fprintf(stderr, "snapshot errno=%d\n", -result);
    assert(!result); assert(!md_inode_snapshot(base, b));
    int lower = open_file(a, "/data/file", O_RDONLY), image = open_file(base, "/data/file", O_RDONLY);
    struct stat first, shared, logical;
    assert(!fstat(lower, &first) && !fstat(image, &shared));
    assert(first.st_dev == shared.st_dev && first.st_ino == shared.st_ino);
    assert(!md_inode_fstat(a, lower, &logical));
    int watch = md_inode_watch_create(a, IN_NONBLOCK);
    assert(watch >= 0);
    int wd = md_inode_watch_add(a, watch, lower, IN_MODIFY | IN_CLOSE_WRITE);
    assert(wd > 0);
    fd = open_file(a, "/data/alias", O_RDWR);
    struct stat copied, stable;
    assert(!fstat(fd, &copied) && !md_inode_fstat(a, fd, &stable));
    assert(copied.st_ino != first.st_ino && stable.st_ino == logical.st_ino
        && stable.st_dev == logical.st_dev && stable.st_nlink == 2);
    assert(pwrite(fd, "upper", 5, 0) == 5);
    assert(md_inode_watch_add(a, watch, fd, IN_MODIFY | IN_CLOSE_WRITE) == wd);
    close(fd);
    char events[4096];
    ssize_t n = md_inode_watch_read(a, watch, events, sizeof(events), NULL, NULL);
    assert(n > 0);
    int modified = 0;
    for (size_t offset = 0; offset < (size_t)n;) {
        struct inotify_event *e = (void *)(events+offset);
        if (e->wd == wd && (e->mask & IN_MODIFY)) modified++;
        offset += sizeof(*e) + e->len;
    }
    assert(modified == 1);
    value(a, "/data/file", "upper"); value(a, "/data/alias", "upper");
    value(b, "/data/file", "lower"); value(base, "/data/file", "lower");
    char old[6] = {0}; assert(pread(lower, old, 5, 0) == 5 && !strcmp(old, "lower"));
    int metadata = md_inode_reopen(a, lower, O_PATH | O_CLOEXEC, 1); assert(metadata >= 0);
    assert(!fstat(metadata, &shared) && shared.st_ino == copied.st_ino); close(metadata);
    assert(!md_inode_rename(a, MD_INODE_ROOT, "/data/file", MD_INODE_ROOT, "/data/renamed", 0));
    assert(!md_inode_unlink(a, MD_INODE_ROOT, "/data/alias", 0));
    assert(!md_inode_unlink(a, MD_INODE_ROOT, "/data/renamed", 0));
    metadata = md_inode_reopen(a, lower, O_RDONLY | O_CLOEXEC, 0); assert(metadata >= 0);
    assert(pread(metadata, old, 5, 0) == 5 && !strcmp(old, "upper")); close(metadata);
    assert(!md_inode_fstat(a, lower, &stable) && stable.st_nlink == 0 && stable.st_ino == logical.st_ino);
    int sibling = open_file(b, "/data/file", O_RDONLY);
    metadata = md_inode_xattr_open(b,sibling,"user.md-copy",W_OK); assert(metadata >= 0);
    char proc[64]; snprintf(proc,sizeof(proc),"/proc/self/fd/%d",metadata);
    assert(!setxattr(proc,"user.md-copy","own",3,0)); close(metadata);
    metadata = md_inode_xattr_open(b,sibling,"",F_OK); assert(metadata >= 0);
    snprintf(proc,sizeof(proc),"/proc/self/fd/%d",metadata);
    char names[256]; ssize_t bytes = listxattr(proc,names,sizeof(names)); assert(bytes > 0);
    int found = 0;
    for (ssize_t i=0;i<bytes;i+=strlen(names+i)+1) found |= !strcmp(names+i,"user.md-copy");
    assert(found && getxattr(proc,"user.md-copy",old,sizeof(old))==3 && !memcmp(old,"own",3));
    close(metadata); close(sibling);
    errno=0; assert(fgetxattr(image,"user.md-copy",old,sizeof(old))==-1 && errno==ENODATA);
    fd = open_file(b, "/data/file", O_RDWR);
    void *map = mmap(NULL, 5, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0); assert(map != MAP_FAILED);
    memcpy(map, "mmaps", 5); assert(!msync(map, 5, MS_SYNC)); munmap(map, 5); close(fd);
    value(b, "/data/alias", "mmaps"); value(base, "/data/file", "lower");
    close(watch); close(lower); close(image);
    md_inode_store_close(b); md_inode_store_close(a); md_inode_store_close(base);
    puts("PASS shared snapshots, copy-up, hardlinks, stable identity, retained FDs, file watches and native mmap");
    return 0;
}
