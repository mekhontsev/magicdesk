#define _GNU_SOURCE
#include "fs_engine.h"
#include <assert.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

/* Same tiny-file checks as fixtures/runtime-workloads.c, without syscall
 * interception or transport. Store preparation is outside the measured interval. */
int main(int argc, char **argv) {
    assert(argc == 2);
    struct md_inode_store *store;
    assert(!md_inode_store_open(argv[1], 0, &store));
    for (unsigned sample = 0; sample < 4; ++sample) {
        struct timespec begin, end;
        assert(!clock_gettime(CLOCK_MONOTONIC, &begin));
        unsigned files = 0;
        for (unsigned pass = 0; pass < 16; ++pass) for (unsigned d = 0; d < 16; ++d) {
            char path[128]; snprintf(path, sizeof(path), "/bench/tree/d%u", d);
            struct md_fs_request q = {.operation = MD_FS_OPEN, .directory = {-1, -1},
                .path = {path, NULL}, .flags = O_RDONLY | O_DIRECTORY | O_CLOEXEC};
            struct md_fs_result dir;
            md_fs_execute(store, NULL, &q, &dir, NULL); assert(!dir.error && dir.fd >= 0);
            unsigned count = 0;
            for (;;) {
                q = (struct md_fs_request){.operation = MD_FS_GETDENTS, .directory = {dir.fd, -1}, .capacity = PATH_MAX};
                struct md_fs_result entries;
                md_fs_execute(store, NULL, &q, &entries, NULL); assert(!entries.error);
                if (!entries.size) break;
                for (unsigned offset = 0; offset < entries.size;) {
                    struct md_inode_dirent *e = (void *)(entries.data + offset);
                    assert(e->size && e->size <= entries.size - offset); offset += e->size;
                    if (!strcmp(e->name, ".") || !strcmp(e->name, "..")) continue;
                    q = (struct md_fs_request){.operation = MD_FS_STAT, .directory = {dir.fd, -1},
                        .path = {e->name, NULL}, .flags = AT_SYMLINK_NOFOLLOW};
                    struct md_fs_result a, opened, b;
                    md_fs_execute(store, NULL, &q, &a, NULL); assert(!a.error);
                    q.operation = MD_FS_OPEN; q.flags = O_RDONLY | O_CLOEXEC;
                    md_fs_execute(store, NULL, &q, &opened, NULL); assert(!opened.error && opened.fd >= 0);
                    q = (struct md_fs_request){.operation = MD_FS_FSTAT, .directory = {opened.fd, -1}};
                    md_fs_execute(store, NULL, &q, &b, NULL); assert(!b.error);
                    assert(a.info.inode == b.info.inode && a.info.device == b.info.device);
                    assert(S_ISREG(b.info.mode) && b.info.size == 32 && b.info.links == 1);
                    unsigned char bytes[32]; assert(read(opened.fd, bytes, sizeof(bytes)) == sizeof(bytes));
                    for (unsigned i = 0; i < sizeof(bytes); ++i) assert(bytes[i] == 42);
                    assert(!close(opened.fd)); ++count; ++files;
                }
            }
            assert(count == 16 && !close(dir.fd));
        }
        assert(files == 4096 && !clock_gettime(CLOCK_MONOTONIC, &end));
        printf("NAMESPACE_METADATA sample=%u files=%u seconds=%.6f\n", sample, files,
            end.tv_sec - begin.tv_sec + (end.tv_nsec - begin.tv_nsec) / 1e9);
    }
    md_inode_store_close(store);
}
