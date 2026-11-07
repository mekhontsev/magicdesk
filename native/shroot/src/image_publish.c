#define _GNU_SOURCE
#include "image_publish.h"
#include "image_maintenance.h"
#include "storage_paths.h"
#include "inode_internal.h"
#include "private_tree.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/random.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

void md_image_publish_close(struct md_image_publish *p) {
    if (p->stage >= 0) { md_private_tree_discard(p->parent, p->temporary); close(p->stage); }
    if (p->parent >= 0) close(p->parent);
    p->stage = p->parent = -1;
}

/* The parent lock closes the mkdir/open/flock race. The retained stage lock
 * distinguishes live publication from a crashed owner, without PID heuristics. */
static int recover(int parent) {
    int fd = openat(parent, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) return -errno;
    DIR *dir = fdopendir(fd);
    if (!dir) { int r = -errno; close(fd); return r; }
    int r = 0;
    for (;;) {
        errno = 0;
        struct dirent *e = readdir(dir);
        if (!e) { if (errno) r = -errno; break; }
        const char *name = e->d_name;
        if (strlen(name) != 42 || strncmp(name, ".md-image-", 10)
                || strspn(name+10, "0123456789abcdef") != 32) continue;
        int stage = openat(parent, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (stage < 0) { if (errno == ENOENT) continue; r = -errno; break; }
        struct stat st;
        if (fstat(stage, &st)) r = -errno;
        else if (st.st_uid != getuid() || (st.st_mode & 0777) != 0700) r = -EACCES;
        else if (flock(stage, LOCK_EX | LOCK_NB)) { if (errno != EWOULDBLOCK) r = -errno; }
        else r = md_private_tree_discard(parent, name);
        close(stage);
        if (r) break;
    }
    closedir(dir); return r;
}
int md_image_publish_recover(const char *directory) {
    int fd = open(directory, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return -errno;
    /* EVENT_WAIT: kernel publication-lock release; caller cancellation/process
     * exit interrupts ownership. No timeout is interpreted as successful recovery. */
    int r = flock(fd, LOCK_EX) ? -errno : recover(fd);
    close(fd); return r;
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
    /* EVENT_WAIT: another publisher finishes its staging handoff; operation
     * cancellation releases this kernel wait and every owned descriptor. */
    int r = flock(p->parent, LOCK_EX) ? -errno : recover(p->parent);
    if (!r) {
        if (!fstatat(p->parent, p->name, &st, AT_SYMLINK_NOFOLLOW)) r = -EEXIST;
        else if (errno != ENOENT) r = -errno;
    }
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
        if (!r && flock(p->stage, LOCK_EX | LOCK_NB)) r = -errno;
    }
    flock(p->parent, LOCK_UN);
    if (!r) {
        char proc[64]; snprintf(proc, sizeof(proc), "/proc/self/fd/%d", p->stage);
        if (!realpath(proc, directory)) r = -errno;
        else if (snprintf(p->path, sizeof(p->path), "%s/store", directory) >= (int)sizeof(p->path)) r = -ENAMETOOLONG;
    }
    if (r) md_image_publish_close(p);
    return r;
}
int md_image_rebase_sources(const char *directory,const char *destination) {
    struct md_inode_store *store=NULL;
    int r=md_inode_store_open_exclusive(directory,&store);
    char previous[PATH_MAX];
    if (!r) r=md_storage_directory_path(store->lifetime->root,previous,sizeof(previous));
    if (!r) r=mdi_runtime_begin(store);
    if (!r) {
        r=md_storage_sources_rebase(store->db,previous,destination);
        store->sync_requested=1;
        r=mdi_commit(store,r);
    }
    md_inode_store_close(store);
    return r;
}

int md_image_publish_commit(struct md_image_publish *p) {
    struct stat metadata;
    if (fstatat(p->stage,"store",&metadata,AT_SYMLINK_NOFOLLOW)) return -errno;
    int directory=S_ISDIR(metadata.st_mode);
    if (directory && !fstatat(p->stage,"store/namespace.db",&metadata,AT_SYMLINK_NOFOLLOW)) {
        char parent[PATH_MAX],destination[PATH_MAX];
        int r=md_storage_directory_path(p->parent,parent,sizeof(parent));
        if (!r && snprintf(destination,sizeof(destination),"%s/%s",parent,p->name)>=(int)sizeof(destination))
            r=-ENAMETOOLONG;
        if (!r) r=md_image_rebase_sources(p->path,destination);
        if (r) return r;
    } else if (directory && errno!=ENOENT) return -errno;
    if (fsync(p->stage)) return -errno;
    if (syscall(SYS_renameat2, p->stage, "store", p->parent, p->name, RENAME_NOREPLACE)) return -errno;
    /* A post-rename sync error is an uncertain durable outcome, not permission
     * to remove or repeat publication. The destination stays for inspection. */
    return fsync(p->parent) ? -errno : 0;
}
