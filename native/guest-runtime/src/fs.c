#define _GNU_SOURCE
#include "fs.h"
#include "proc_paths.h"
#include "raw.h"
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>

int md_fs_guest(const struct md_fs *fs, const char *host, char *out) {
    size_t n = md_length(fs->root);
    if (md_prefix(host, fs->root) && (!host[n] || host[n] == '/'))
        return md_copy(out, PATH_MAX, host[n] ? host + n : "/");
    if (md_host_path(host)) return md_copy(out, PATH_MAX, host);
    return -EXDEV;
}
static int host_path(const struct md_fs *fs, const char *path, char *out) {
    if (md_host_path(path)) return md_copy(out, PATH_MAX, path);
    int r = md_copy(out, PATH_MAX, fs->root);
    return r ? r : md_append(out, PATH_MAX, path);
}
int md_fs_resolve(const struct md_fs *fs, int dirfd, const char *path, int follow, char *out) {
    if (!path) return -EFAULT;
    if (!*path) return -ENOENT;
    // Host procfs/devfs have kernel-defined magic links, not guest symlinks.
    if (md_host_path(path)) {
        struct md_proc_path ref = md_proc_path(path);
        if (ref.kind == MD_PROC_ROOT && (follow == MD_PATH_FOLLOW || *ref.tail))
            path = *ref.tail ? ref.tail : "/";
        else return md_copy(out, PATH_MAX, path);
    }
    char todo[PATH_MAX], resolved[PATH_MAX] = "/";
    long r;
    if (path[0] == '/') {
        if ((r = md_copy(todo, sizeof(todo), path))) return (int)r;
    } else {
        if (dirfd == AT_FDCWD) {
            if ((r = RAW2(getcwd, out, PATH_MAX)) < 0) return (int)r;
        } else {
            struct stat info;
            if ((r = RAW2(fstat, dirfd, &info)) < 0) return (int)r;
            if (!S_ISDIR(info.st_mode)) return -ENOTDIR;
            char link[64] = "/proc/self/fd/";
            md_decimal(link + 14, (unsigned)dirfd);
            r = RAW4(readlinkat, AT_FDCWD, link, out, PATH_MAX);
            if (r < 0) return (int)r;
            if (r >= PATH_MAX) return -ENAMETOOLONG;
            out[r] = 0;
        }
        if ((r = md_fs_guest(fs, out, todo)) || (r = md_append(todo, sizeof(todo), "/"))
                || (r = md_append(todo, sizeof(todo), path))) return (int)r;
    }
    unsigned links = 0;
    while (*todo) {
        char *start = todo; while (*start == '/') ++start;
        if (!*start) break;
        char *end = start; while (*end && *end != '/') ++end;
        size_t size = (size_t)(end - start);
        if (size > NAME_MAX) return -ENAMETOOLONG;
        char component[NAME_MAX + 1];
        memcpy(component, start, size); component[size] = 0;
        int directory = *end != 0;
        memmove(todo, end, md_length(end) + 1);
        size_t rest = 0; while (todo[rest] == '/') ++rest;
        // Entry mutations leave the last component (including dot/slash) to the kernel.
        if (follow == MD_PATH_ENTRY && !todo[rest]) {
            if (resolved[1] && (r = md_append(resolved, sizeof(resolved), "/"))) return (int)r;
            if ((r = md_append(resolved, sizeof(resolved), component))
                    || (r = md_append(resolved, sizeof(resolved), todo))) return (int)r;
            return host_path(fs, resolved, out);
        }
        if (md_equal(component, ".")) continue;
        if (md_equal(component, "..")) {
            size_t n = md_length(resolved);
            while (n > 1 && resolved[n - 1] != '/') --n;
            resolved[n > 1 ? n - 1 : 1] = 0;
            continue;
        }
        size_t parent_length = md_length(resolved);
        if (resolved[1] && (r = md_append(resolved, sizeof(resolved), "/"))) return (int)r;
        if ((r = md_append(resolved, sizeof(resolved), component))) return (int)r;
        if (md_host_path(resolved)) {
            if ((r = md_copy(out, PATH_MAX, resolved))) return (int)r;
            if ((r = md_append(out, PATH_MAX, todo))) return (int)r;
            if (md_host_path(out)) return 0;
        }
        if ((r = host_path(fs, resolved, out))) return (int)r;
        if (!directory && !follow) break;
        struct stat info;
        r = RAW4(newfstatat, AT_FDCWD, out, &info, AT_SYMLINK_NOFOLLOW);
        if (r < 0) {
            size_t rest = 0; while (todo[rest] == '/') ++rest;
            if (r == -ENOENT && !todo[rest]) {
                if (directory && (r = md_append(resolved, sizeof(resolved), "/"))) return (int)r;
                break;
            }
            return (int)r;
        }
        if (S_ISLNK(info.st_mode)) {
            if (++links > 40) return -ELOOP;
            char target[PATH_MAX];
            r = RAW4(readlinkat, AT_FDCWD, out, target, sizeof(target));
            if (r < 0) return (int)r;
            if ((size_t)r >= sizeof(target)) return -ENAMETOOLONG;
            target[r] = 0;
            if ((r = md_append(target, sizeof(target), todo))) return (int)r;
            resolved[target[0] == '/' ? 1 : parent_length] = 0;
            md_copy(todo, sizeof(todo), target);
        } else if (directory && !S_ISDIR(info.st_mode)) return -ENOTDIR;
    }
    return host_path(fs, resolved, out);
}
