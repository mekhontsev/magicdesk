#ifndef MD_FS_MOUNT_INTERNAL_H
#define MD_FS_MOUNT_INTERNAL_H
#include "fs_mounts.h"
#include <limits.h>

enum { MD_VIEW_BUCKETS = 4096, MD_VIEW_OBJECT_LIMIT = 65536 };
struct md_view_mount { int source, target, readonly; struct stat native, guest; };
struct md_view_object {
    struct md_view_object *next;
    struct stat identity;
    int fd, mount;
    char id[33];
};
struct md_fs_mounts {
    unsigned count, objects;
    struct md_view_mount mounts[MD_FS_MOUNTS_MAX];
    struct md_view_object *table[MD_VIEW_BUCKETS];
};
struct md_view_location { int parent, fd, mount, boundary; char name[NAME_MAX+1]; };
int md_view_same(const struct stat *, const struct stat *);
int md_view_record(struct md_filesystem *, int fd, int mount, struct md_view_object **);
int md_view_identity(struct md_filesystem *, int fd, struct md_view_object **);
int md_view_path(struct md_filesystem *, int fd, int mount, char *, size_t);
int md_view_walk(struct md_filesystem *, int base, const char *path, int follow, int missing,
        uint64_t resolve, struct md_view_location *);
void md_view_location_close(struct md_view_location *);
int md_view_fdpath(int fd, char *, size_t);
#endif
