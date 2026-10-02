#define _GNU_SOURCE
#include "command_access.h"
#include "inode_store.h"
#include "guest_identity.h"
#include "../../command-client/wire.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int prepare_directory(const char *path) {
    struct md_inode_store *store = NULL;
    int r = md_inode_store_open(path, 0, &store);
    // Preparation supplies only an empty mountpoint, never a credential or a client in the image.
    const char *directories[] = {"/run", MD_COMMAND_GUEST_DIRECTORY};
    for (unsigned i = 0; !r && i < sizeof(directories)/sizeof(*directories); i++) {
        struct stat st;
        r = md_inode_stat(store, -1, directories[i], AT_SYMLINK_NOFOLLOW, &st);
        if (r == -ENOENT) r = md_inode_mkdir(store, -1, directories[i], 0755);
        else if (!r && !S_ISDIR(st.st_mode)) r = -ENOTDIR;
    }
    md_inode_store_close(store);
    return r;
}

int md_guest_command_access(const char *store, struct md_fs_attachment *attachment, char source[PATH_MAX]) {
    if (!store) return -EINVAL;
    ssize_t n = readlink("/proc/self/exe", source, PATH_MAX-1);
    if (n <= 0 || n >= PATH_MAX-1) return n < 0 ? -errno : -ENAMETOOLONG;
    source[n] = 0;
    char *last = strrchr(source, '/');
    if (!last || (size_t)(last-source)+sizeof("/commands") > PATH_MAX) return -ENAMETOOLONG;
    strcpy(last, "/commands");
    char executable[PATH_MAX];
    if (snprintf(executable, sizeof(executable), "%s/magicdesk", source) >= (int)sizeof(executable)) return -ENAMETOOLONG;
    if (access(executable, R_OK | X_OK)) return -errno;
    int r = prepare_directory(store);
    if (r) return r;
    int fd = md_command_lease();
    if (fd < 0) return fd;
    const char *path = getenv("PATH");
    char *search = NULL;
    if (!r && asprintf(&search, MD_COMMAND_GUEST_DIRECTORY ":%s", path ? path : "/usr/bin:/bin") < 0) r = -ENOMEM;
    if (!r && setenv("PATH", search, 1)) r = -errno;
    free(search);
    if (r) { close(fd); return r; }
    *attachment = (struct md_fs_attachment){.source=source, .target=MD_COMMAND_GUEST_DIRECTORY, .readonly=1};
    return fd;
}
