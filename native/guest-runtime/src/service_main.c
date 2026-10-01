#define _GNU_SOURCE
#include "fs_service.h"
#include "image_catalogue.h"
#include "launch_identity.h"
#include "raw.h"
#include <errno.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/resource.h>
#include <signal.h>

static void cost(const char *name, const struct md_cost *value) {
    fprintf(stderr, "MD_STORE phase=%s calls=%llu ns=%llu\n", name,
        (unsigned long long)value->calls, (unsigned long long)value->nanoseconds);
}

static int import(const char *source, const char *destination, int preserve_ownership) {
    if (source[0] != '/' || destination[0] != '/' || !strcmp(source, "/") || !strcmp(destination, "/")) return 2;
    int fd = open(source, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) { perror("open import source"); return 1; }
    struct md_inode_store *store = NULL;
    struct md_inode_import_limits limits = {2ULL * 1024 * 1024 * 1024, 200000, preserve_ownership};
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
    sigset_t mask; sigemptyset(&mask); sigaddset(&mask, SIGPIPE);
    if (sigprocmask(SIG_BLOCK, &mask, NULL)) return 2;
    md_page_size = (size_t)sysconf(_SC_PAGESIZE);
    if (md_page_size < 4096 || md_page_size > 65536 || (md_page_size & (md_page_size - 1))) return 2;
    int statistics = argc > 1 && !strcmp(argv[1], "--statistics");
    if (statistics) { argc--; argv++; }
    if ((argc == 4 || argc == 5) && !strcmp(argv[1], "--import")) {
        if (argc == 5 && strcmp(argv[4], "--preserve-ownership")) return 2;
        return import(argv[2], argv[3], argc == 5);
    }
    if (argc != 5 && argc != 6) return 2;
    char *end;
    long ready = strtol(argv[3], &end, 10);
    if (*end || ready < 3 || ready > INT32_MAX) return 2;
    long stop = strtol(argv[4], &end, 10);
    if (*end || stop < 3 || stop > INT32_MAX || stop == ready) return 2;
    /* Dedicated service process. Every creation request carries its already-masked mode. */
    umask(0);
    struct md_inode_store *store = NULL;
    struct md_image_catalogue *images = NULL;
    struct md_inode_statistics database = {0};
    struct md_fs_statistics filesystem = {0};
    int error = md_inode_store_open(argv[1], 0, &store), listener = -1;
    if (!error && argc == 6) error = md_image_catalogue_open(store, argv[5], &images);
    if (!error) {
        listener = md_fs_listen(argv[2]);
        if (listener < 0)
            error = listener;
    }
    if (write(ready, &error, sizeof(error)) != sizeof(error))
        error = -EPIPE;
    close(ready);
    if (!error && statistics) md_inode_measure(store, &database);
    if (!error)
        error = md_fs_serve(&(struct md_filesystem){.store=store,.images=images}, listener, stop, 5000, statistics ? &filesystem : NULL, NULL);
    if (statistics) {
        cost("prepare", &database.prepare); cost("step", &database.step);
        cost("transaction", &database.transaction); cost("lock", &database.lock);
        fprintf(stderr, "MD_STORE queryReuses=%llu\n", (unsigned long long)database.query_reuses);
        for (unsigned i = 0; i <= MD_FS_LAST; i++) if (filesystem.operation[i].calls)
            fprintf(stderr, "MD_FS operation=%u calls=%llu ns=%llu\n", i,
                (unsigned long long)filesystem.operation[i].calls,
                (unsigned long long)filesystem.operation[i].nanoseconds);
        struct rusage usage;
        if (!getrusage(RUSAGE_SELF, &usage)) fprintf(stderr, "MD_CPU service userUs=%llu systemUs=%llu\n",
            (unsigned long long)usage.ru_utime.tv_sec * 1000000 + usage.ru_utime.tv_usec,
            (unsigned long long)usage.ru_stime.tv_sec * 1000000 + usage.ru_stime.tv_usec);
    }
    if (listener >= 0)
        close(listener);
    md_image_catalogue_close(images);
    md_inode_store_close(store);
    close(stop);
    return error ? 1 : 0;
}
