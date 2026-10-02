#define _GNU_SOURCE
#include "image_prepare.h"
#include "image_io.h"
#include "inode_internal.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

/* Offline configuration owns the store's exclusive lifetime, not a live guest's files. */
int md_image_resolver(const char *path, const char *text, int replace) {
    if (strlen(text) > 8192) return -E2BIG;
    struct md_inode_store *s = NULL;
    int r = md_inode_store_open_exclusive(path, &s);
    if (!r && s->readonly) r = -EROFS;
    struct md_identity identity = md_identity_new(0, 0);
    if (!r) s->identity = &identity;
    struct stat st;
    if (!r) {
        r = md_inode_stat(s, -1, "/etc/resolv.conf", AT_SYMLINK_NOFOLLOW, &st);
        if (r == -ENOENT) r = 0;
        else if (!r && !replace && (!S_ISREG(st.st_mode) || st.st_size)) {
            puts("Resolver configuration retained");
            goto done;
        } else if (!r && !S_ISREG(st.st_mode) && !S_ISLNK(st.st_mode)) r = -EINVAL;
    }
    if (!r) {
        r = md_inode_mkdir(s, -1, "/etc", 0755);
        if (r == -EEXIST) r = 0;
    }
    char temporary[96];
    snprintf(temporary, sizeof(temporary), "/etc/.magicdesk-resolv-%ld", (long)getpid());
    int fd = !r ? md_inode_create(s, -1, temporary, 0644) : -1;
    if (!r && fd < 0) r = fd;
    if (!r) r = md_image_write(fd, text, strlen(text));
    if (!r && fsync(fd)) r = -errno;
    if (fd >= 0) close(fd);
    if (!r) r = md_inode_rename(s, -1, temporary, -1, "/etc/resolv.conf", 0);
    if (r && fd >= 0) md_inode_unlink(s, -1, temporary, 0);
    if (!r) puts("Resolver configuration written");
done:
    md_inode_store_close(s);
    md_identity_release(&identity);
    return r;
}
