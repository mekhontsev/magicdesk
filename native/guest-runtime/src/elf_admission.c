#define _GNU_SOURCE
#include "elf_admission.h"
#include "fs_rpc.h"
#include "elf.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

void md_admission_close(struct md_admission *a) {
    if (a->source >= 0) close(a->source);
    md_exec_image_release(a->image);
    *a = (struct md_admission){.source = -1};
}
int md_admission_open(struct md_admission *a, const char *endpoint, const char *path) {
    struct md_fs_request q = {.operation = MD_FS_OPEN, .directory = {-1, -1},
        .path = {path, NULL}, .flags = O_RDONLY | O_CLOEXEC};
    struct md_fs_result response;
    int error = md_fs_call(endpoint, 5000, &q, &response);
    if (!error) error = response.error;
    if (error) { fprintf(stderr, "PROBE admission source-open error=%d\n", error); return error; }
    error = md_admission_snapshot(a, response.fd);
    if (error) return error;
    q = (struct md_fs_request){.operation = MD_FS_OBJECT_ID, .directory = {a->source, -1}};
    error = md_fs_call(endpoint, 5000, &q, &response);
    if (!error) error = response.error;
    if (!error && response.size != sizeof(a->object)) error = -EPROTO;
    if (!error) memcpy(a->object, response.data, sizeof(a->object));
    if (error) md_admission_close(a);
    return error;
}
int md_admission_snapshot(struct md_admission *a, int source) {
    int error = 0;
    a->source = source;
    if (fstat(a->source, &a->source_stat)) error = -errno;
    if (!error && (!S_ISREG(a->source_stat.st_mode) || a->source_stat.st_size <= 0
            || a->source_stat.st_size > 8 * 1024 * 1024)) error = -EFBIG;
    int copy = !error ? syscall(SYS_memfd_create, "md-admitted-elf", MFD_CLOEXEC | MFD_ALLOW_SEALING) : -1;
    if (!error && copy < 0) error = -errno;
    char buffer[65536];
    for (off_t offset = 0; !error && offset < a->source_stat.st_size;) {
        size_t n = a->source_stat.st_size - offset;
        if (n > sizeof(buffer)) n = sizeof(buffer);
        ssize_t got = pread(a->source, buffer, n, offset);
        if (got < 0 && errno == EINTR) continue;
        if (got != (ssize_t)n) { error = got < 0 ? -errno : -EIO; break; }
        for (size_t written = 0; written < n;) {
            ssize_t count = pwrite(copy, buffer + written, n - written, offset + written);
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) { error = count < 0 ? -errno : -EIO; break; }
            written += count;
        }
        offset += n;
    }
    struct stat after;
    if (!error && fstat(a->source, &after)) error = -errno;
    if (!error && (after.st_size != a->source_stat.st_size
            || after.st_mtim.tv_sec != a->source_stat.st_mtim.tv_sec
            || after.st_mtim.tv_nsec != a->source_stat.st_mtim.tv_nsec
            || after.st_ctim.tv_sec != a->source_stat.st_ctim.tv_sec
            || after.st_ctim.tv_nsec != a->source_stat.st_ctim.tv_nsec)) error = -ESTALE;
    if (error) fprintf(stderr, "PROBE admission snapshot error=%d\n", error);
    if (!error && fcntl(copy, F_ADD_SEALS, F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL)) {
        error = -errno; fprintf(stderr, "PROBE admission seal error=%d\n", error);
    }
    if (!error) { error = md_elf_validate(copy, 0); if (error) fprintf(stderr, "PROBE admission ELF error=%d\n", error); }
    if (!error) { error = md_exec_image_admit(copy, 0, 0, S_IFREG | 04755, &a->image);
        if (error) fprintf(stderr, "PROBE admission identity error=%d\n", error); }
    if (copy >= 0) close(copy);
    if (error) md_admission_close(a);
    return error;
}
int md_admission_match(const struct md_admission *a, const char *endpoint, int fd) {
    struct stat st;
    if (fstat(fd, &st)) return -errno;
    int match = a->image && st.st_dev == a->source_stat.st_dev && st.st_ino == a->source_stat.st_ino;
    if (!match && a->object[0]) {
        struct md_fs_request q = {.operation = MD_FS_OBJECT_ID, .directory = {fd, -1}};
        struct md_fs_result response;
        int error = md_fs_call(endpoint, 5000, &q, &response);
        if (error) return error;
        if (response.error == -EXDEV) return 0;
        if (response.error) return response.error;
        if (response.size != sizeof(a->object) || memcmp(response.data, a->object, sizeof(a->object))) return 0;
        const int required = F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL;
        int seals = fcntl(fd, F_GET_SEALS);
        if (seals < 0 || (seals & required) != required || st.st_size != a->source_stat.st_size) return -ESTALE;
        char left[4096], right[4096];
        for (off_t offset = 0; offset < st.st_size; offset += sizeof(left)) {
            size_t n = st.st_size - offset;
            if (n > sizeof(left)) n = sizeof(left);
            if (pread(fd, left, n, offset) != (ssize_t)n || pread(a->source, right, n, offset) != (ssize_t)n)
                return -EIO;
            if (memcmp(left, right, n)) return -ESTALE;
        }
        return 1;
    }
    if (match && (st.st_size != a->source_stat.st_size
            || st.st_ctim.tv_sec != a->source_stat.st_ctim.tv_sec
            || st.st_ctim.tv_nsec != a->source_stat.st_ctim.tv_nsec)) return -ESTALE;
    return match;
}
