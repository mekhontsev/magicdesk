#define _GNU_SOURCE
#include "fs_service.h"
#include "launch_identity.h"
#include <errno.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>

static int import(const char *source, const char *destination) {
    if (source[0] != '/' || destination[0] != '/' || !strcmp(source, "/") || !strcmp(destination, "/")) return 2;
    int fd = open(source, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) { perror("open import source"); return 1; }
    struct md_inode_store *store = NULL;
    struct md_inode_import_limits limits = {2ULL * 1024 * 1024 * 1024, 200000};
    struct md_inode_import_result result = {0};
    int error = md_inode_store_open(destination, 1, &store);
    if (!error) error = md_inode_import_tree(store, fd, &limits, &result);
    md_inode_store_close(store);
    close(fd);
    if (error) { fprintf(stderr, "Guest import failed: errno=%d\n", -error); return 1; }
    printf("Imported entries=%llu bytes=%llu aliases=%llu\n", (unsigned long long)result.entries,
            (unsigned long long)result.bytes, (unsigned long long)result.aliases);
    return 0;
}

int main(int argc, char **argv) {
    if (!md_launch_identity(getuid(), geteuid(), getgid(), getegid()))
        return 2;
    if (argc == 4 && !strcmp(argv[1], "--import")) return import(argv[2], argv[3]);
    if (argc != 5) return 2;
    char *end;
    long ready = strtol(argv[3], &end, 10);
    if (*end || ready < 3 || ready > INT32_MAX) return 2;
    long stop = strtol(argv[4], &end, 10);
    if (*end || stop < 3 || stop > INT32_MAX || stop == ready) return 2;
    /* Dedicated service process. Every creation request carries its already-masked mode. */
    umask(0);
    struct md_inode_store *store = NULL;
    int error = md_inode_store_open(argv[1], 0, &store), listener = -1;
    if (!error) {
        listener = md_fs_listen(argv[2]);
        if (listener < 0)
            error = listener;
    }
    if (write(ready, &error, sizeof(error)) != sizeof(error))
        error = -EPIPE;
    close(ready);
    if (!error)
        error = md_fs_serve(store, listener, stop, 5000);
    if (listener >= 0)
        close(listener);
    md_inode_store_close(store);
    close(stop);
    return error ? 1 : 0;
}
