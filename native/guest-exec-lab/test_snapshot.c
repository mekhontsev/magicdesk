#define _GNU_SOURCE
#include "inode_store.h"
#include "inode_watch.h"
#include "inode_internal.h"
#include "fs_operation.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/file.h>
#include <sys/mman.h>
#include <sys/xattr.h>
#include <unistd.h>

static struct md_inode_store *store(const char *base, const char *name) {
    char path[4096]; assert(snprintf(path, sizeof(path), "%s/%s", base, name) < (int)sizeof(path));
    struct md_inode_store *s = NULL; assert(md_inode_store_open(path, 1, &s) == 0); return s;
}
static struct md_inode_store *reopen(const char *base, const char *name) {
    char path[4096]; assert(snprintf(path,sizeof(path),"%s/%s",base,name)<(int)sizeof(path));
    struct md_inode_store *s=NULL; assert(!md_inode_store_open(path,0,&s)); return s;
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
static void source_object(struct md_inode_store *s, int retained, const struct stat *expected) {
    char source[96]; assert(!md_inode_descriptor_source(s,retained,source,sizeof(source),1));
    int flags=O_PATH|O_NOFOLLOW|O_CLOEXEC;
    int reopened=open(source,flags); assert(reopened>=0);
    struct stat actual; assert(!fstat(reopened,&actual));
    assert(actual.st_dev==expected->st_dev && actual.st_ino==expected->st_ino
        && actual.st_mode==expected->st_mode && fcntl(reopened,F_GETFL)==(flags&~O_CLOEXEC));
    close(reopened);
}
static void generations(const char *path) {
    struct md_inode_store *base=store(path,"generation-base"), *child=store(path,"generation-child");
    const char *names[]={"/unchanged","/changed"};
    struct stat original[2],logical;
    for (int i=0;i<2;++i) {
        int fd=open_file(base,names[i],O_CREAT|O_RDWR);
        assert(write(fd,"lower",5)==5); close(fd);
    }
    assert(!md_inode_link(base,MD_INODE_ROOT,"/changed",MD_INODE_ROOT,"/alias",0));
    assert(!md_inode_store_seal(base));
    for (int i=0;i<2;++i) {
        int fd=open_file(base,names[i],O_RDONLY);
        assert(!fstat(fd,&original[i])); close(fd);
    }
    assert(!md_inode_snapshot(base,child));
    int fd=open_file(child,"/alias",O_RDWR);
    struct stat copied;
    assert(pwrite(fd,"upper",5,0)==5 && !fstat(fd,&copied) && !md_inode_fstat(child,fd,&logical)); close(fd);
    assert(copied.st_ino!=original[1].st_ino);
    assert(!md_inode_store_seal(child));
    struct md_inode_store *next=store(path,"generation-next");
    assert(!md_inode_snapshot(child,next));
    md_inode_store_close(next); md_inode_store_close(child); md_inode_store_close(base);
    next=reopen(path,"generation-next");
    for (int i=0;i<2;++i) {
        struct stat actual;
        fd=open_file(next,names[i],O_RDONLY);
        assert(!fstat(fd,&actual));
        const struct stat *expected=i ? &copied : &original[0];
        assert(actual.st_dev==expected->st_dev && actual.st_ino==expected->st_ino);
        source_object(next,fd,expected); close(fd);
    }
    struct stat alias;
    assert(!md_inode_stat(next,MD_INODE_ROOT,"/alias",0,&alias));
    assert(alias.st_dev==logical.st_dev && alias.st_ino==logical.st_ino && alias.st_nlink==2);
    fd=open_file(next,"/changed",O_RDWR); assert(pwrite(fd,"third",5,0)==5); close(fd);
    value(next,"/alias","third"); value(next,"/unchanged","lower");
    child=reopen(path,"generation-child"); base=reopen(path,"generation-base");
    value(child,"/alias","upper"); value(base,"/alias","lower");
    md_inode_store_close(base); md_inode_store_close(child); md_inode_store_close(next);
    puts("PASS reopened COW generations retain direct source bodies, hardlink identity and independent writes");
}
int main(int argc, char **argv) {
    if (argc == 4 && !strcmp(argv[1], "--imported")) {
        struct md_inode_store *s = NULL, *other = NULL;
        assert(!md_inode_store_open(argv[2], 0, &s));
        assert(md_inode_store_open_exclusive(argv[2], &other) == -EBUSY && !other);
        int source = open(argv[3], O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        assert(source >= 0 && flock(source, LOCK_EX | LOCK_NB) == -1 && errno == EWOULDBLOCK);
        int old = open_file(s, "/data/file", O_RDONLY);
        struct stat before, after;
        assert(!md_inode_fstat(s, old, &before));
        int fd = open_file(s, "/data/alias", O_RDWR);
        assert(pwrite(fd, "upper", 5, 0) == 5);
        assert(!md_inode_fstat(s, fd, &after));
        assert(before.st_ino == after.st_ino && before.st_dev == after.st_dev && after.st_nlink == 2);
        close(fd);
        value(s, "/data/file", "upper"); value(s, "/data/alias", "upper");
        char old_data[5]; assert(pread(old, old_data, 5, 0) == 5 && !memcmp(old_data, "lower", 5));
        close(old); md_inode_store_close(s);
        assert(!flock(source, LOCK_EX | LOCK_NB)); close(source);
        assert(!md_inode_store_open_exclusive(argv[2], &s)); md_inode_store_close(s);
        puts("PASS imported pool copy-up, hardlink identity, retained FD and lifetime leases");
        return 0;
    }
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
    source_object(a,lower,&first);
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
    source_object(a,lower,&first);
    int sibling = open_file(b, "/data/file", O_RDONLY);
    struct md_fs_request attr={.operation=MD_FS_XATTR_OBJECT,.directory={sibling,-1},
        .path={NULL,"user.md-copy"},.mode=MD_FS_XATTR_SET,.value="own",.value_size=3,.capacity=3};
    struct md_fs_result reply={.fd=-1};
    md_inode_xattr(b,&attr,&reply,NULL); assert(!reply.error && reply.fd<0);
    char names[256]; struct md_fs_output output={.data=names,.capacity=sizeof(names)};
    attr.mode=MD_FS_XATTR_LIST; attr.path[1]=NULL; attr.value=NULL; attr.value_size=0; attr.capacity=sizeof(names);
    md_inode_xattr(b,&attr,&reply,&output); assert(!reply.error && reply.position>0);
    ssize_t bytes=reply.position;
    int found = 0;
    for (ssize_t i=0;i<bytes;i+=strlen(names+i)+1) found |= !strcmp(names+i,"user.md-copy");
    attr.mode=MD_FS_XATTR_GET; attr.path[1]="user.md-copy"; attr.capacity=sizeof(old);
    output=(struct md_fs_output){.data=old,.capacity=sizeof(old)};
    md_inode_xattr(b,&attr,&reply,&output);
    assert(found && !reply.error && reply.position==3 && !memcmp(old,"own",3));
    close(sibling);
    errno=0; assert(fgetxattr(image,"user.md-copy",old,sizeof(old))==-1 && errno==ENODATA);
    fd = open_file(b, "/data/file", O_RDWR);
    void *map = mmap(NULL, 5, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0); assert(map != MAP_FAILED);
    memcpy(map, "mmaps", 5); assert(!msync(map, 5, MS_SYNC)); munmap(map, 5); close(fd);
    value(b, "/data/alias", "mmaps"); value(base, "/data/file", "lower");
    close(watch); close(lower); close(image);
    md_inode_store_close(b); md_inode_store_close(a); md_inode_store_close(base);
    generations(argv[1]);
    puts("PASS backing-source lease retains the exact lower object across copy-up, rename and unlink");
    puts("PASS shared snapshots, copy-up, hardlinks, stable identity, retained FDs, file watches and native mmap");
    return 0;
}
