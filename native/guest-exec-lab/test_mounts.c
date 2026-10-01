#define _GNU_SOURCE
#include "fs_mounts.h"
#include "inode_watch.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/openat2.h>
#include <stdio.h>
#include <string.h>
#include <sys/inotify.h>
#include <unistd.h>

static struct md_fs_result call(struct md_filesystem *fs, unsigned op, int fd, const char *path,
        unsigned flags, unsigned mode, void *data, size_t size) {
    struct md_fs_request q = {.operation=op,.directory={fd,-1},.path={path,NULL},.flags=flags,.mode=mode,.capacity=(unsigned)size};
    struct md_fs_output output = {.data=data,.capacity=size};
    struct md_fs_result r;
    md_fs_execute(fs, &q, &r, &output); return r;
}
static int opened(struct md_filesystem *fs, const char *path, unsigned flags) {
    struct md_fs_result r = call(fs, MD_FS_OPEN, -1, path, flags, 0600, NULL, 0);
    if (r.error) fprintf(stderr, "open %s: %d\n", path, r.error);
    assert(!r.error && r.fd >= 0); return r.fd;
}
static int rejected(void *context, const void *data, size_t size) {
    (void)context; (void)data; (void)size; return -EFAULT;
}
int main(int argc, char **argv) {
    assert(argc == 2 && argv[1][0] == '/'); assert(!mkdir(argv[1], 0700));
    char path[4096], host[4096], readonly[4096], store[4096];
    assert(snprintf(host, sizeof(host), "%s/host", argv[1]) < (int)sizeof(host));
    assert(snprintf(readonly, sizeof(readonly), "%s/readonly", argv[1]) < (int)sizeof(readonly));
    assert(snprintf(store, sizeof(store), "%s/store", argv[1]) < (int)sizeof(store));
    assert(!mkdir(host, 0700) && !mkdir(readonly, 0700));
    struct md_inode_store *s = NULL; assert(!md_inode_store_open(store, 1, &s));
    assert(!md_inode_mkdir(s, -1, "/volume", 0700));
    assert(!md_inode_mkdir(s, -1, "/readonly", 0700));
    int fd = md_inode_open(s, -1, "/guest", O_CREAT | O_RDWR, 0600); assert(fd >= 0);
    assert(write(fd, "guest", 5) == 5); close(fd);
    assert(!md_inode_symlink(s, "/volume", -1, "/link"));
    struct md_fs_attachment attachments[] = {{host,"/volume",0},{readonly,"/readonly",1}};
    struct md_filesystem fs = {.store=s};
    assert(!md_fs_mounts_open(&fs, attachments, 2));
    fd = opened(&fs, "/link/file", O_RDWR | O_CREAT); assert(write(fd, "native", 6) == 6);
    snprintf(path, sizeof(path), "%s/file", host);
    struct stat st, native; assert(!fstat(fd, &st) && !stat(path, &native));
    assert(st.st_dev == native.st_dev && st.st_ino == native.st_ino);
    int directory = opened(&fs, "/volume", O_RDONLY | O_DIRECTORY);
    struct md_fs_result result = call(&fs, MD_FS_OPEN, directory, "../guest", O_RDONLY, 0, NULL, 0);
    assert(!result.error); char bytes[4096] = {0}; assert(read(result.fd, bytes, 5) == 5 && !strcmp(bytes, "guest")); close(result.fd);
    assert(!symlinkat("/guest", directory, "absolute"));
    int guest = opened(&fs, "/volume/absolute", O_RDONLY); close(guest);
    int symlink_fd = opened(&fs, "/volume/absolute", O_PATH | O_NOFOLLOW);
    result = call(&fs, MD_FS_REOPEN, symlink_fd, NULL, O_PATH | O_NOFOLLOW, 0, NULL, 0);
    assert(!result.error && !fstat(result.fd, &st) && S_ISLNK(st.st_mode)); close(result.fd); close(symlink_fd);
    assert(!symlinkat("/volume", directory, "self"));
    result = call(&fs, MD_FS_UNLINK, -1, "/volume/self/", AT_REMOVEDIR, 0, NULL, 0);
    assert(result.error == -ENOTDIR);
    result = call(&fs, MD_FS_OPEN, -1, "/volume/file", O_RDONLY | O_TRUNC, 0, NULL, 0);
    assert(result.error == -EINVAL);
    int watch = md_inode_watch_create(s, IN_NONBLOCK); assert(watch >= 0);
    int wd = md_inode_watch_add(s, watch, directory, IN_CREATE | IN_MOVED_FROM | IN_MOVED_TO); assert(wd > 0);
    int observed = opened(&fs, "/volume/observed", O_CREAT | O_WRONLY); close(observed);
    assert(!renameat(directory, "observed", directory, "renamed"));
    char events[4096]; ssize_t event_bytes = md_inode_watch_read(s, watch, events, sizeof(events), NULL, NULL);
    assert(event_bytes > 0); unsigned mask = 0;
    for (size_t at = 0; at < (size_t)event_bytes;) {
        struct inotify_event *event = (void *)(events+at);
        assert(event->wd == wd); mask |= event->mask; at += sizeof(*event)+event->len;
    }
    assert((mask & (IN_CREATE | IN_MOVED_FROM | IN_MOVED_TO)) == (IN_CREATE | IN_MOVED_FROM | IN_MOVED_TO));
    close(watch);
    result = call(&fs, MD_FS_OPEN, -1, "/readonly/new", O_CREAT | O_RDWR, 0600, NULL, 0); assert(result.error == -EROFS);
    snprintf(path, sizeof(path), "%s/file", readonly);
    int external = open(path, O_CREAT | O_WRONLY | O_CLOEXEC, 0600); assert(external >= 0); close(external);
    int ro = opened(&fs, "/readonly/file", O_RDONLY);
    result = call(&fs, MD_FS_XATTR_OPEN, ro, "user.test", 0, W_OK, NULL, 0); assert(result.error == -EROFS);
    result = call(&fs, MD_FS_XATTR_OPEN, ro, "", 0, F_OK, NULL, 0); assert(result.error == -EXDEV);
    result = call(&fs, MD_FS_REOPEN, ro, NULL, O_PATH | O_CLOEXEC, 1, NULL, 0); assert(result.error == -EROFS);
    assert(!unlink(path));
    result = call(&fs, MD_FS_REOPEN, ro, NULL, O_RDWR, 0, NULL, 0); assert(result.error == -EROFS); close(ro);
    result = call(&fs, MD_FS_PATH, directory, NULL, 0, 0, bytes, sizeof(bytes));
    assert(!result.error && !strcmp(bytes, "/volume"));
    struct md_fs_request q = {.operation=MD_FS_OPEN,.directory={directory,-1},.path={"../guest",NULL},.flags=O_RDONLY,.resolve=RESOLVE_BENEATH};
    md_fs_execute(&fs, &q, &result, NULL); assert(result.error == -EXDEV);
    q.resolve = RESOLVE_NO_XDEV; md_fs_execute(&fs, &q, &result, NULL); assert(result.error == -EXDEV);
    q.path[0] = "/file"; q.resolve = RESOLVE_IN_ROOT;
    md_fs_execute(&fs, &q, &result, NULL); assert(!result.error); close(result.fd);
    q = (struct md_fs_request){.operation=MD_FS_RENAME,.directory={-1,-1},.path={"/guest","/volume/guest"}};
    md_fs_execute(&fs, &q, &result, NULL); assert(result.error == -EXDEV);
    result = call(&fs, MD_FS_UNLINK, -1, "/volume", AT_REMOVEDIR, 0, NULL, 0); assert(result.error == -EBUSY);
    q = (struct md_fs_request){.operation=MD_FS_GETDENTS,.directory={directory,-1},.capacity=sizeof(bytes)};
    struct md_fs_output output = {.data=bytes,.capacity=sizeof(bytes),.deliver=rejected};
    md_fs_execute(&fs, &q, &result, &output); assert(result.error == -EFAULT && lseek(directory, 0, SEEK_CUR) == 0);
    output.deliver = NULL; md_fs_execute(&fs, &q, &result, &output); assert(!result.error && result.size > 0);
    assert(!unlinkat(directory, "file", 0));
    result = call(&fs, MD_FS_REOPEN, fd, NULL, O_RDONLY | O_CLOEXEC, 0, NULL, 0); assert(!result.error);
    memset(bytes, 0, sizeof(bytes)); assert(read(result.fd, bytes, 6) == 6 && !strcmp(bytes, "native")); close(result.fd);
    close(fd); close(directory);
    md_fs_mounts_close(&fs); md_inode_store_close(s);
    puts("PASS native attachments, cross-boundary symlinks/dirfds, readonly retained FDs, mount identity and directory publication");
    return 0;
}
