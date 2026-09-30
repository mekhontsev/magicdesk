#ifndef MD_FS_H
#define MD_FS_H
#include <linux/limits.h>
struct md_process_image;
struct md_socket_routes;
struct md_fs {
    char root[PATH_MAX];
    char endpoint[108];
    const struct md_process_image *image;
    const struct md_socket_routes *connections;
};
enum md_path_follow { MD_PATH_NOFOLLOW, MD_PATH_FOLLOW, MD_PATH_ENTRY };
int md_fs_guest(const struct md_fs *, const char *, char *);
int md_fs_resolve(const struct md_fs *, int, const char *, int, char *);
#endif
