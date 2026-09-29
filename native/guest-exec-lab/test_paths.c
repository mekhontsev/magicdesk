#define _GNU_SOURCE
#include "fs.h"
#include "proc_paths.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static void expect(struct md_fs *g, const char *path, int follow, const char *suffix) {
    char actual[PATH_MAX], wanted[PATH_MAX];
    assert(!md_fs_resolve(g, AT_FDCWD, path, follow, actual));
    snprintf(wanted, sizeof(wanted), "%s%s", g->root, suffix);
    assert(!strcmp(actual, wanted));
}

int main(int argc, char **argv) {
    assert(argc == 2);
    struct md_fs g = {0};
    assert(realpath(argv[1], g.root));
    assert(!chdir(g.root));
    assert(md_host_path("/dev/null") && md_host_path("//./dev/./null"));
    assert(md_host_path("/dev/shmallow") && !md_host_path("/dev/shm"));
    assert(!md_host_path("//dev/./shm/file") && md_host_path("/proc/self"));
    assert(!md_host_path("/process") && !md_host_path("relative"));
    assert(!mkdir("dev", 0700) && !mkdir("dev/shm", 0700));
    expect(&g, "/dev/shm/value", 1, "/dev/shm/value");
    assert(!mkdir("etc", 0700));
    assert(!mkdir("etc/sub", 0700));
    int fd = open("etc/value", O_CREAT | O_WRONLY, 0600);
    assert(fd >= 0);
    close(fd);
    assert(!symlink("/etc/value", "absolute"));
    assert(!symlink("etc/sub", "relative"));
    assert(!symlink("loop", "loop"));
    expect(&g, "/absolute", 1, "/etc/value");
    expect(&g, "/absolute", 0, "/absolute");
    expect(&g, "/relative/../value", 1, "/etc/value");
    expect(&g, "/../../etc/value", 1, "/etc/value");
    expect(&g, "/etc/new", 1, "/etc/new");
    expect(&g, "/etc/new/", 0, "/etc/new/");
    expect(&g, "/etc/sub/", MD_PATH_ENTRY, "/etc/sub/");
    expect(&g, "/etc/sub/.", MD_PATH_ENTRY, "/etc/sub/.");
    expect(&g, "/relative/", MD_PATH_ENTRY, "/relative/");
    expect(&g, "/relative/../sub/", MD_PATH_ENTRY, "/etc/sub/");
    expect(&g, "etc/./sub/../value", 1, "/etc/value");
    char actual[PATH_MAX];
    assert(md_fs_resolve(&g, AT_FDCWD, "/loop", 1, actual) == -ELOOP);
    assert(md_fs_resolve(&g, AT_FDCWD, "", 1, actual) == -ENOENT);
    assert(md_fs_resolve(&g, AT_FDCWD, "/missing/../etc", 1, actual) == -ENOENT);
    assert(md_fs_resolve(&g, AT_FDCWD, "/etc/value/..", 1, actual) == -ENOTDIR);
    assert(md_fs_resolve(&g, AT_FDCWD, "/etc/value/", 0, actual) == -ENOTDIR);
    fd = open("etc", O_RDONLY | O_DIRECTORY);
    assert(fd >= 0);
    assert(!md_fs_resolve(&g, fd, "value", 1, actual));
    assert(strstr(actual, "/etc/value"));
    close(fd);
    fd = open("/", O_RDONLY | O_DIRECTORY);
    assert(fd >= 0);
    assert(md_fs_resolve(&g, fd, "value", 1, actual) == -EXDEV);
    close(fd);
    assert(md_fs_guest(&g, "/outside", actual) == -EXDEV);
    char sibling[PATH_MAX];
    assert(snprintf(sibling, sizeof(sibling), "%s-other/file", g.root) < (int)sizeof(sibling));
    assert(md_fs_guest(&g, sibling, actual) == -EXDEV);
    puts("PASS path resolution: symlinks, dirfd, dotdot, missing paths, outside descriptors");
    return 0;
}
